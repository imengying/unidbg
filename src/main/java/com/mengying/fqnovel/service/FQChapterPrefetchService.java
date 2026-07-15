package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQDirectoryResponse;
import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.dto.FQNovelRequest;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.dto.ItemContent;
import com.mengying.fqnovel.utils.LocalCacheFactory;
import com.mengying.fqnovel.utils.ThrottledLogger;
import com.mengying.fqnovel.utils.Texts;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 单章接口的抗风控优化：
 * - 根据目录预取一段章节（调用上游 batch_full）
 * - 将结果缓存，后续单章请求直接命中缓存，显著减少上游调用次数
 */
@Service
public class FQChapterPrefetchService {

    private static final Logger log = LoggerFactory.getLogger(FQChapterPrefetchService.class);

    private static final int MIN_DIRECTORY_CACHE_MAX_ENTRIES = 64;
    private static final int MAX_CHAPTER_PREFETCH_SIZE = 30;
    private static final long CHAPTER_BASE_WEIGHT_BYTES = 256L;
    private static final long STRING_BASE_WEIGHT_BYTES = 40L;
    private static final int UTF16_BYTES_PER_CHAR = 2;

    private static final String EX_PREFIX_ILLEGAL_ARGUMENT = "java.lang.IllegalArgumentException:";
    private static final String EX_PREFIX_ILLEGAL_STATE = "java.lang.IllegalStateException:";
    private static final String EX_PREFIX_RUNTIME = "java.lang.RuntimeException:";
    private static final String[] FAILURE_REASON_PREFIXES = {
        EX_PREFIX_ILLEGAL_ARGUMENT,
        EX_PREFIX_ILLEGAL_STATE,
        EX_PREFIX_RUNTIME
    };
    
    private final FQDownloadProperties downloadProperties;
    private final FQNovelService fqNovelService;
    private final FQDirectoryService fqDirectoryService;
    private final ChapterContentBuilder chapterContentBuilder;
    private final AutoRestartService autoRestartService;
    private final ObjectProvider<PgChapterCacheService> pgChapterCacheServiceProvider;
    @Qualifier("fqPrefetchExecutor")
    private final Executor prefetchExecutor;

    private Cache<String, FQNovelChapterInfo> chapterCache;
    private Cache<String, String> chapterNegativeCache;
    private Cache<String, String> chapterRetryBackoffCache;
    private Cache<String, DirectoryIndex> directoryCache;
    private ThrottledLogger chapterFailureThrottledLog = new ThrottledLogger(0L);
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inflightPrefetch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DirectoryIndex>> inflightDirectory = new ConcurrentHashMap<>();

    public FQChapterPrefetchService(
        FQDownloadProperties downloadProperties,
        FQNovelService fqNovelService,
        FQDirectoryService fqDirectoryService,
        ChapterContentBuilder chapterContentBuilder,
        AutoRestartService autoRestartService,
        ObjectProvider<PgChapterCacheService> pgChapterCacheServiceProvider,
        @Qualifier("fqPrefetchExecutor") Executor prefetchExecutor
    ) {
        this.downloadProperties = downloadProperties;
        this.fqNovelService = fqNovelService;
        this.fqDirectoryService = fqDirectoryService;
        this.chapterContentBuilder = chapterContentBuilder;
        this.autoRestartService = autoRestartService;
        this.pgChapterCacheServiceProvider = pgChapterCacheServiceProvider;
        this.prefetchExecutor = prefetchExecutor;
    }

    @PostConstruct
    public void initCaches() {
        long chapterMaxWeight = Math.max(1L, downloadProperties.getCache().getChapterMaxWeightBytes());
        int chapterAuxiliaryMax = Math.max(1, downloadProperties.getCache().getChapterMaxEntries());
        long chapterTtl = downloadProperties.getCache().getChapterTtlMs();
        long chapterNegativeTtl = Math.max(0L, downloadProperties.getCache().getChapterNegativeTtlMs());
        long chapterFailureLogCooldown = Math.max(0L, downloadProperties.getCache().getChapterFailureLogCooldownMs());
        long chapterEmptyRetryBackoff = Math.max(0L, downloadProperties.getCache().getChapterEmptyRetryBackoffMs());
        int dirMax = Math.max(
            MIN_DIRECTORY_CACHE_MAX_ENTRIES,
            downloadProperties.getCache().getApiDirectoryMaxEntries()
        );
        long dirTtl = downloadProperties.getCache().getApiDirectoryTtlMs();

        this.chapterCache = LocalCacheFactory.buildWeighted(
            chapterMaxWeight,
            chapterTtl,
            FQChapterPrefetchService::estimateChapterWeight
        );
        this.chapterNegativeCache = chapterNegativeTtl > 0
            ? LocalCacheFactory.build(chapterAuxiliaryMax, chapterNegativeTtl)
            : null;
        this.chapterRetryBackoffCache = chapterEmptyRetryBackoff > 0
            ? LocalCacheFactory.build(chapterAuxiliaryMax, chapterEmptyRetryBackoff)
            : null;
        this.directoryCache = LocalCacheFactory.build(dirMax, dirTtl);
        this.chapterFailureThrottledLog = new ThrottledLogger(chapterFailureLogCooldown);
    }

    private static int estimateChapterWeight(String cacheKey, FQNovelChapterInfo chapterInfo) {
        if (chapterInfo == null) {
            return 1;
        }

        long weight = CHAPTER_BASE_WEIGHT_BYTES
            + estimateStringWeight(cacheKey)
            + estimateStringWeight(chapterInfo.getChapterId())
            + estimateStringWeight(chapterInfo.getBookId())
            + estimateStringWeight(chapterInfo.getAuthorName())
            + estimateStringWeight(chapterInfo.getTitle())
            + estimateStringWeight(chapterInfo.getRawContent())
            + estimateStringWeight(chapterInfo.getPrevChapterId())
            + estimateStringWeight(chapterInfo.getNextChapterId())
            + estimateStringWeight(chapterInfo.getTxtContent());
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, weight));
    }

    private static long estimateStringWeight(String value) {
        if (value == null) {
            return 0L;
        }
        return STRING_BASE_WEIGHT_BYTES + (long) value.length() * UTF16_BYTES_PER_CHAR;
    }

    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(FQNovelRequest request) {
        if (request == null) {
            return errorFuture("请求不能为空");
        }

        final String bookId = Texts.trimToNull(request.getBookId());
        if (bookId == null) {
            return errorFuture("书籍ID不能为空");
        }

        final String chapterId = Texts.trimToNull(request.getChapterId());
        if (chapterId == null) {
            return errorFuture("章节ID不能为空");
        }

        FQNovelChapterInfo cached = getCachedChapter(bookId, chapterId);
        if (cached != null) {
            return CompletableFuture.completedFuture(FQNovelResponse.success(cached));
        }

        // 主缓存：PostgreSQL（命中后回填本地 Caffeine）
        FQNovelChapterInfo persisted = getPersistedChapter(bookId, chapterId);
        if (persisted != null) {
            return CompletableFuture.completedFuture(FQNovelResponse.success(persisted));
        }

        String cachedFailure = getCachedChapterFailure(bookId, chapterId);
        if (cachedFailure != null) {
            return errorFuture("获取章节内容失败: " + cachedFailure);
        }

        String backoffFailure = getChapterRetryBackoffFailure(bookId, chapterId);
        if (backoffFailure != null) {
            return errorFuture("获取章节内容失败: " + backoffFailure);
        }

        // 预取：优先在目录中定位章节顺序，拉取后缓存（非阻塞链式调用，避免线程池互等死锁）
        return prefetchAndCacheDedup(bookId, chapterId)
            .exceptionally(ex -> null) // 预取失败不影响单章兜底
            .thenCompose(ignored -> {
                FQNovelChapterInfo afterPrefetch = getCachedChapter(bookId, chapterId);
                if (afterPrefetch != null) {
                    return CompletableFuture.completedFuture(FQNovelResponse.success(afterPrefetch));
                }

                // 兜底：仍未命中则只取单章
                return fqNovelService.batchFull(chapterId, bookId, true).thenApply(single -> {
                    if (single.code() != 0 || single.data() == null) {
                        return FQNovelResponse.<FQNovelChapterInfo>error("获取章节内容失败: " + single.message());
                    }

                    Map<String, ItemContent> dataMap = single.data().data();
                    if (dataMap == null || dataMap.isEmpty()) {
                        return FQNovelResponse.<FQNovelChapterInfo>error("未找到章节数据");
                    }

                    ItemContent itemContent = dataMap.get(chapterId);
                    if (itemContent == null) {
                        log.warn("单章兜底请求未返回目标章节 - bookId: {}, chapterId: {}",
                            bookId, chapterId);
                        return FQNovelResponse.<FQNovelChapterInfo>error(
                            "获取章节内容失败: 上游未返回目标章节 " + chapterId
                        );
                    }
                    try {
                        FQNovelChapterInfo info = chapterContentBuilder.buildChapterInfo(bookId, chapterId, itemContent);
                        if (!cacheChapter(bookId, chapterId, info)) {
                            throw new IllegalArgumentException("章节内容为空/过短");
                        }
                        autoRestartService.recordSuccess();
                        return FQNovelResponse.success(info);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            })
            .exceptionally(e -> {
                Throwable t = unwrapCompletionException(e);
                String msg = exceptionMessage(t);
                recordChapterFailure(bookId, chapterId, msg);
                if (isChapterWarnLevelFailure(msg)) {
                    logChapterWarnThrottled(bookId, chapterId, msg, t);
                } else {
                    log.error("单章获取失败 - bookId: {}, chapterId: {}", bookId, chapterId, t);
                }
                return FQNovelResponse.<FQNovelChapterInfo>error("获取章节内容失败: " + msg);
            });
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private static String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        return Objects.requireNonNullElse(throwable.getMessage(), throwable.toString());
    }

    private Executor resolvePrefetchExecutor() {
        return prefetchExecutor != null ? prefetchExecutor : ForkJoinPool.commonPool();
    }

    private CompletableFuture<Void> prefetchAndCacheDedup(String bookId, String chapterId) {
        final String key = computePrefetchKeyFast(bookId, chapterId);

        CompletableFuture<Void> existing = inflightPrefetch.get(key);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Void> created = new CompletableFuture<>();
        existing = inflightPrefetch.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }

        doPrefetchAndCacheAsync(bookId, chapterId).whenComplete((v, ex) -> {
            try {
                if (ex != null) {
                    log.debug("预取失败（忽略） - bookId: {}, chapterId: {}", bookId, chapterId, ex);
                    created.completeExceptionally(ex);
                    return;
                }
                created.complete(null);
            } finally {
                inflightPrefetch.remove(key, created);
            }
        });

        return created;
    }

    private String computePrefetchKeyFast(String bookId, String chapterId) {
        DirectoryIndex directoryIndex = directoryCache.getIfPresent(bookId);
        if (directoryIndex == null || directoryIndex.itemIds().isEmpty()) {
            return singlePrefetchKey(bookId, chapterId);
        }
        int index = directoryIndex.indexOf(chapterId);
        if (index < 0) {
            return singlePrefetchKey(bookId, chapterId);
        }
        int size = prefetchBatchSize();
        int bucketStart = bucketStartFor(index, size);
        return bookId + ":bucket:" + bucketStart + ":" + size;
    }

    private static String singlePrefetchKey(String bookId, String chapterId) {
        return bookId + ":single:" + chapterId;
    }

    private static <T> CompletableFuture<FQNovelResponse<T>> errorFuture(String message) {
        return CompletableFuture.completedFuture(FQNovelResponse.error(message));
    }

    private int prefetchBatchSize() {
        return Math.max(1, Math.min(MAX_CHAPTER_PREFETCH_SIZE, downloadProperties.getPrefetch().getChapterSize()));
    }

    private List<String> selectPrefetchBatchIds(List<String> itemIds, int chapterIndex, String chapterId) {
        if (chapterIndex < 0) {
            return Collections.singletonList(chapterId);
        }
        int size = prefetchBatchSize();
        int bucketStart = bucketStartFor(chapterIndex, size);
        int endExclusive = Math.min(itemIds.size(), bucketStart + size);
        return itemIds.subList(bucketStart, endExclusive);
    }

    private static int bucketStartFor(int chapterIndex, int batchSize) {
        if (chapterIndex < 0 || batchSize <= 0) {
            return 0;
        }
        return chapterIndex / batchSize * batchSize;
    }

    private CompletableFuture<Void> doPrefetchAndCacheAsync(String bookId, String chapterId) {
        Executor exec = resolvePrefetchExecutor();
        return getDirectoryIndexAsync(bookId).thenCompose(directoryIndex -> {
            List<String> itemIds = directoryIndex != null ? directoryIndex.itemIds() : List.of();
            if (itemIds.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            int index = directoryIndex.indexOf(chapterId);
            List<String> batchIds = selectPrefetchBatchIds(itemIds, index, chapterId);

            // 拉取并解密（处理放在 prefetchExecutor 上，避免占用业务线程池）
            String joined = String.join(",", batchIds);
            return fqNovelService.batchFull(joined, bookId, true).thenAcceptAsync(batch -> {
                if (batch == null || batch.code() != 0 || batch.data() == null || batch.data().data() == null) {
                    return;
                }

                Map<String, ItemContent> dataMap = batch.data().data();
                String firstBatchRiskReason = null;
                boolean targetChapterValid = false;
                for (String itemId : batchIds) {
                    ItemContent content = dataMap.get(itemId);
                    if (content == null) {
                        continue;
                    }
                    try {
                        FQNovelChapterInfo info = chapterContentBuilder.buildChapterInfo(bookId, itemId, content);
                        if (!cacheChapter(bookId, itemId, info)) {
                            throw new IllegalArgumentException("章节内容为空/过短");
                        }
                        if (chapterId.equals(itemId)) {
                            targetChapterValid = true;
                        }
                    } catch (Exception e) {
                        String normalizedReason = recordChapterFailure(bookId, itemId, e.getMessage(), false);
                        if (firstBatchRiskReason == null
                            && UpstreamSignedRequestService.REASON_CHAPTER_EMPTY_OR_SHORT.equals(
                                UpstreamSignedRequestService.resolveRetryReason(normalizedReason))) {
                            firstBatchRiskReason = normalizedReason;
                        }
                        log.debug("预取章节处理失败 - bookId: {}, itemId: {}", bookId, itemId, e);
                    }
                }
                handleChapterRiskSignal(firstBatchRiskReason);
                if (targetChapterValid) {
                    autoRestartService.recordSuccess();
                }
            }, exec);
        });
    }

    private CompletableFuture<DirectoryIndex> getDirectoryIndexAsync(String bookId) {
        DirectoryIndex cached = directoryCache.getIfPresent(bookId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<DirectoryIndex> inFlight = inflightDirectory.get(bookId);
        if (inFlight != null) {
            return inFlight;
        }

        CompletableFuture<DirectoryIndex> created = new CompletableFuture<>();
        inFlight = inflightDirectory.putIfAbsent(bookId, created);
        if (inFlight != null) {
            return inFlight;
        }

        try {
            FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
            directoryRequest.setBookId(bookId);
            directoryRequest.setMinimalResponse(true);

            fqDirectoryService.getBookDirectory(directoryRequest)
                .handle((resp, ex) -> {
                    if (ex != null || resp == null || resp.code() != 0 || resp.data() == null || resp.data().getItemDataList() == null) {
                        return DirectoryIndex.empty();
                    }

                    List<String> itemIds = new ArrayList<>();
                    Map<String, Integer> chapterIndex = new HashMap<>();
                    for (FQDirectoryResponse.ItemData item : resp.data().getItemDataList()) {
                        if (item != null && Texts.hasText(item.getItemId())) {
                            String itemId = Texts.trimToEmpty(item.getItemId());
                            chapterIndex.put(itemId, itemIds.size());
                            itemIds.add(itemId);
                        }
                    }

                    DirectoryIndex directoryIndex = DirectoryIndex.of(itemIds, chapterIndex);
                    directoryCache.put(bookId, directoryIndex);
                    return directoryIndex;
                })
                .whenComplete((directoryIndex, ex) ->
                    completeDirectoryInflight(bookId, created, directoryIndex));
        } catch (Exception e) {
            completeDirectoryInflight(bookId, created, DirectoryIndex.empty());
        }

        return created;
    }

    private void completeDirectoryInflight(
        String bookId,
        CompletableFuture<DirectoryIndex> inflightFuture,
        DirectoryIndex directoryIndex
    ) {
        inflightFuture.complete(Objects.requireNonNullElse(directoryIndex, DirectoryIndex.empty()));
        inflightDirectory.remove(bookId, inflightFuture);
    }

    private record DirectoryIndex(List<String> itemIds, Map<String, Integer> chapterIndex) {
        private static final DirectoryIndex EMPTY = new DirectoryIndex(List.of(), Map.of());

        private static DirectoryIndex of(List<String> itemIds, Map<String, Integer> chapterIndex) {
            List<String> safeIds = itemIds == null ? List.of() : List.copyOf(itemIds);
            Map<String, Integer> safeIndex = chapterIndex == null ? Map.of() : Map.copyOf(chapterIndex);
            return new DirectoryIndex(safeIds, safeIndex);
        }

        private static DirectoryIndex empty() {
            return EMPTY;
        }

        private int indexOf(String chapterId) {
            if (chapterId == null) {
                return -1;
            }
            Integer index = chapterIndex.get(chapterId);
            return index != null ? index : -1;
        }
    }

    private FQNovelChapterInfo getCachedChapter(String bookId, String chapterId) {
        String key = cacheKey(bookId, chapterId);
        FQNovelChapterInfo cached = chapterCache.getIfPresent(key);
        if (!FQNovelChapterInfo.normalizeAndValidateForCache(bookId, chapterId, cached)) {
            if (cached != null) {
                chapterCache.invalidate(key);
            }
            return null;
        }
        return cached;
    }

    private FQNovelChapterInfo getPersistedChapter(String bookId, String chapterId) {
        PgChapterCacheService pgCacheService = pgChapterCacheServiceProvider.getIfAvailable();
        if (pgCacheService == null) {
            return null;
        }

        FQNovelChapterInfo persisted = pgCacheService.getChapter(bookId, chapterId);
        if (persisted != null) {
            chapterCache.put(cacheKey(bookId, chapterId), persisted);
            evictChapterFailure(bookId, chapterId);
            evictChapterRetryBackoff(bookId, chapterId);
        }
        return persisted;
    }

    private boolean cacheChapter(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (!FQNovelChapterInfo.normalizeAndValidateForCache(bookId, chapterId, chapterInfo)) {
            return false;
        }

        chapterCache.put(cacheKey(bookId, chapterId), chapterInfo);
        evictChapterFailure(bookId, chapterId);
        evictChapterRetryBackoff(bookId, chapterId);

        PgChapterCacheService pgCacheService = pgChapterCacheServiceProvider.getIfAvailable();
        if (pgCacheService != null) {
            pgCacheService.saveChapterIfValid(bookId, chapterId, chapterInfo);
        }
        return true;
    }

    private static String cacheKey(String bookId, String chapterId) {
        return bookId + ":" + chapterId;
    }

    private String getCachedChapterFailure(String bookId, String chapterId) {
        if (chapterNegativeCache == null) {
            return null;
        }
        String reason = chapterNegativeCache.getIfPresent(cacheKey(bookId, chapterId));
        if (!Texts.hasText(reason)) {
            return null;
        }
        return reason;
    }

    private void cacheChapterFailure(String bookId, String chapterId, String reason) {
        if (!isChapterNegativeCacheEligible(bookId, chapterId)) {
            return;
        }
        String normalized = normalizeFailureReason(reason);
        if (!isChapterFailureCacheable(normalized)) {
            return;
        }
        chapterNegativeCache.put(cacheKey(bookId, chapterId), normalized);
    }

    private String getChapterRetryBackoffFailure(String bookId, String chapterId) {
        if (!isChapterRetryBackoffEligible(bookId, chapterId)) {
            return null;
        }
        String reason = chapterRetryBackoffCache.getIfPresent(cacheKey(bookId, chapterId));
        return Texts.hasText(reason) ? reason : null;
    }

    private void cacheChapterRetryBackoff(String bookId, String chapterId, String reason) {
        if (!isChapterRetryBackoffEligible(bookId, chapterId)) {
            return;
        }
        String normalized = normalizeFailureReason(reason);
        if (!isChapterRetryBackoffReason(normalized)) {
            return;
        }
        chapterRetryBackoffCache.put(cacheKey(bookId, chapterId), normalized);
    }

    private void evictChapterFailure(String bookId, String chapterId) {
        if (!isChapterNegativeCacheEligible(bookId, chapterId)) {
            return;
        }
        chapterNegativeCache.invalidate(cacheKey(bookId, chapterId));
    }

    private void evictChapterRetryBackoff(String bookId, String chapterId) {
        if (!isChapterRetryBackoffEligible(bookId, chapterId)) {
            return;
        }
        chapterRetryBackoffCache.invalidate(cacheKey(bookId, chapterId));
    }

    private boolean isChapterNegativeCacheEligible(String bookId, String chapterId) {
        return chapterNegativeCache != null && Texts.hasText(bookId) && Texts.hasText(chapterId);
    }

    private boolean isChapterRetryBackoffEligible(String bookId, String chapterId) {
        return chapterRetryBackoffCache != null && Texts.hasText(bookId) && Texts.hasText(chapterId);
    }

    private static String normalizeFailureReason(String reason) {
        if (!Texts.hasText(reason)) {
            return "";
        }
        String normalized = Texts.trimToEmpty(reason);
        return stripExceptionPrefix(normalized);
    }

    private static String stripExceptionPrefix(String reason) {
        for (String prefix : FAILURE_REASON_PREFIXES) {
            if (reason.startsWith(prefix)) {
                return Texts.trimToEmpty(reason.substring(prefix.length()));
            }
        }
        return reason;
    }

    private static boolean isChapterFailureCacheable(String reason) {
        if (!Texts.hasText(reason)) {
            return false;
        }
        // 空内容/过短内容通常是瞬时上游异常或风控抖动，不应被负缓存放大。
        return reason.contains("upstream item code=");
    }

    private void recordChapterFailure(String bookId, String chapterId, String reason) {
        recordChapterFailure(bookId, chapterId, reason, true);
    }

    private String recordChapterFailure(String bookId, String chapterId, String reason, boolean countRiskSignal) {
        String normalizedReason = normalizeFailureReason(reason);
        cacheChapterFailure(bookId, chapterId, normalizedReason);
        cacheChapterRetryBackoff(bookId, chapterId, normalizedReason);
        if (countRiskSignal) {
            handleChapterRiskSignal(normalizedReason);
        }
        return normalizedReason;
    }

    private static boolean isChapterRetryBackoffReason(String reason) {
        if (!Texts.hasText(reason)) {
            return false;
        }
        return reason.contains("章节内容为空/过短") || reason.contains("Encrypted data too short");
    }

    private static boolean isChapterWarnLevelFailure(String reason) {
        if (!Texts.hasText(reason)) {
            return false;
        }
        return isChapterRetryBackoffReason(reason) || reason.contains("upstream item code=");
    }

    private void handleChapterRiskSignal(String normalizedReason) {
        String retryReason = UpstreamSignedRequestService.resolveRetryReason(normalizedReason);
        if (!UpstreamSignedRequestService.REASON_CHAPTER_EMPTY_OR_SHORT.equals(retryReason)) {
            return;
        }
        FQEncryptServiceWorker.requestGlobalReset(retryReason);
        autoRestartService.recordFailure(retryReason);
    }

    private void logChapterWarnThrottled(String bookId, String chapterId, String reason, Throwable throwable) {
        String throttleKey = "chapter.warn:" + cacheKey(bookId, chapterId) + ":" + normalizeFailureReason(reason);
        if (!chapterFailureThrottledLog.shouldLog(throttleKey)) {
            return;
        }
        log.warn("单章获取失败 - bookId: {}, chapterId: {}, reason={}", bookId, chapterId, reason);
        log.debug("单章获取失败详情 - bookId: {}, chapterId: {}", bookId, chapterId, throwable);
    }

}
