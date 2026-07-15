package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.config.FQConstants;
import com.mengying.fqnovel.utils.ThrottledLogger;
import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQDirectoryResponse;
import com.mengying.fqnovel.dto.FQNovelBookInfo;
import com.mengying.fqnovel.dto.FQNovelBookInfoResp;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.dto.FqIBatchFullResponse;
import com.mengying.fqnovel.utils.FQApiUtils;
import com.mengying.fqnovel.utils.ProcessLifecycle;
import com.mengying.fqnovel.utils.RetryBackoff;
import com.mengying.fqnovel.utils.Texts;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class FQNovelService {

    private static final Logger log = LoggerFactory.getLogger(FQNovelService.class);

    private static final String DIRECTORY_FAILURE_PREFIX = "获取书籍目录失败: ";
    private static final String CHAPTER_FETCH_FAILURE_PREFIX = "获取章节内容失败: ";

    private final FQApiUtils fqApiUtils;
    private final FQDirectoryService fqDirectoryService;
    private final FQDownloadProperties downloadProperties;
    private final FQDeviceRotationService deviceRotationService;
    private final AutoRestartService autoRestartService;
    private final UpstreamSignedRequestService upstreamSignedRequestService;
    private final ObjectMapper objectMapper;
    @Qualifier("applicationTaskExecutor")
    private final Executor taskExecutor;
    private final ThrottledLogger throttledLog = new ThrottledLogger(60_000L);

    public FQNovelService(
        FQApiUtils fqApiUtils,
        FQDirectoryService fqDirectoryService,
        FQDownloadProperties downloadProperties,
        FQDeviceRotationService deviceRotationService,
        AutoRestartService autoRestartService,
        UpstreamSignedRequestService upstreamSignedRequestService,
        ObjectMapper objectMapper,
        @Qualifier("applicationTaskExecutor") Executor taskExecutor
    ) {
        this.fqApiUtils = fqApiUtils;
        this.fqDirectoryService = fqDirectoryService;
        this.downloadProperties = downloadProperties;
        this.deviceRotationService = deviceRotationService;
        this.autoRestartService = autoRestartService;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
    }

    public CompletableFuture<FQNovelResponse<FqIBatchFullResponse>> batchFull(String itemIds, String bookId, boolean download) {
        return CompletableFuture.supplyAsync(() -> executeBatchFullWithRetry(itemIds, bookId, download), taskExecutor);
    }

    private FQNovelResponse<FqIBatchFullResponse> executeBatchFullWithRetry(String itemIds, String bookId, boolean download) {
        if (ProcessLifecycle.isShuttingDown()) {
            return FQNovelResponse.error("服务正在退出中，请稍后重试");
        }

        int maxAttempts = Math.max(1, downloadProperties.getRetry().getMaxRetries());
        long baseDelayMs = Math.max(0L, downloadProperties.getRetry().getDelayMs());
        long maxDelayMs = Math.max(baseDelayMs, downloadProperties.getRetry().getMaxDelayMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchBatchFullOnce(itemIds, bookId, download);
            } catch (Exception e) {
                FQNovelResponse<FqIBatchFullResponse> decision =
                    handleBatchFullException(e, itemIds, attempt, maxAttempts, baseDelayMs, maxDelayMs);
                if (decision != null) {
                    return decision;
                }
            }
        }
        return FQNovelResponse.error("获取章节内容失败: 超过最大重试次数");
    }

    private FQNovelResponse<FqIBatchFullResponse> fetchBatchFullOnce(String itemIds, String bookId, boolean download) throws Exception {
        String url = fqApiUtils.getBaseUrl() + FQConstants.Chapter.BATCH_FULL_PATH;
        Map<String, String> params = fqApiUtils.buildBatchFullParams(itemIds, bookId, download);
        String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

        UpstreamSignedRequestService.UpstreamRawResult upstream =
            upstreamSignedRequestService.executeSignedRawGetRateLimited(fullUrl, fqApiUtils.buildCommonHeaders());
        if (upstream == null) {
            throw new IllegalStateException("签名生成失败");
        }

        String responseBody = upstream.responseBody();
        String trimmedBody = Texts.trimToNull(responseBody);
        if (trimmedBody == null) {
            throw new RuntimeException("Empty upstream response");
        }
        if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
            if (UpstreamSignedRequestService.containsIllegalAccess(trimmedBody)) {
                throw new IllegalStateException(UpstreamSignedRequestService.REASON_ILLEGAL_ACCESS);
            }
            throw new IllegalStateException(UpstreamSignedRequestService.REASON_UPSTREAM_NON_JSON);
        }

        FqIBatchFullResponse batchResponse = objectMapper.readValue(responseBody, FqIBatchFullResponse.class);
        if (batchResponse == null) {
            throw new RuntimeException("Upstream parse failed");
        }
        if (batchResponse.code() != 0) {
            String msg = Texts.trimToEmpty(batchResponse.message());
            String raw = Texts.trimToEmpty(responseBody);
            if (batchResponse.code() == 110L
                || UpstreamSignedRequestService.containsIllegalAccess(msg)
                || UpstreamSignedRequestService.containsIllegalAccess(raw)) {
                throw new IllegalStateException(UpstreamSignedRequestService.REASON_ILLEGAL_ACCESS);
            }
            return FQNovelResponse.error((int) batchResponse.code(), msg);
        }

        FQEncryptServiceWorker.recordUpstreamSuccess();
        return FQNovelResponse.success(batchResponse);
    }

    private FQNovelResponse<FqIBatchFullResponse> handleBatchFullException(
        Exception e,
        String itemIds,
        int attempt,
        int maxAttempts,
        long baseDelayMs,
        long maxDelayMs
    ) {
        String message = Texts.defaultIfBlank(Texts.trimToEmpty(e.getMessage()), e.getClass().getSimpleName());
        String retryReason = UpstreamSignedRequestService.resolveRetryReason(message);
        boolean retryable = retryReason != null;

        if (!retryable || attempt >= maxAttempts) {
            if (retryable) {
                autoRestartService.recordFailure(retryReason);
            }
            String userMessage = batchFailureMessage(retryReason);
            if (Texts.hasText(userMessage)) {
                return FQNovelResponse.error(userMessage);
            }
            if (throttledLog.shouldLog("batch.failure")) {
                log.error("获取章节内容失败 - itemIds: {}", itemIds, e);
            }
            return FQNovelResponse.error(CHAPTER_FETCH_FAILURE_PREFIX + message);
        }

        if (UpstreamSignedRequestService.REASON_SIGNER_FAIL.equals(retryReason)
            || (UpstreamSignedRequestService.REASON_UPSTREAM_EMPTY.equals(retryReason)
            && attempt >= 2)) {
            FQEncryptServiceWorker.requestGlobalReset(retryReason);
        }
        // 所有可重试异常都遵循设备切换冷却，避免高并发时在设备池里来回抖动。
        deviceRotationService.rotateIfNeeded(retryReason);

        long delay = RetryBackoff.computeDelay(
            baseDelayMs,
            maxDelayMs,
            attempt,
            10,
            0L,
            250L,
            false
        );
        if (!RetryBackoff.sleep(delay)) {
            return FQNovelResponse.error("获取章节内容失败: 重试被中断");
        }

        return null;
    }

    private static String batchFailureMessage(String retryReason) {
        if (retryReason == null) {
            return null;
        }
        return switch (retryReason) {
            case UpstreamSignedRequestService.REASON_ILLEGAL_ACCESS ->
                "获取章节内容失败: ILLEGAL_ACCESS（已重试仍失败，建议更换设备/降低频率）";
            case UpstreamSignedRequestService.REASON_UPSTREAM_GZIP ->
                "获取章节内容失败: 响应格式异常（已重试仍失败）";
            case UpstreamSignedRequestService.REASON_UPSTREAM_NON_JSON ->
                "获取章节内容失败: 上游返回非JSON（已重试仍失败）";
            case UpstreamSignedRequestService.REASON_UPSTREAM_EMPTY ->
                "获取章节内容失败: 空响应（已重试仍失败）";
            case UpstreamSignedRequestService.REASON_CHAPTER_EMPTY_OR_SHORT ->
                "获取章节内容失败: 章节内容为空/过短（已重试仍失败）";
            case UpstreamSignedRequestService.REASON_SIGNER_FAIL ->
                "获取章节内容失败: 签名生成失败（已重试仍失败）";
            default -> null;
        };
    }

    private static String directoryFailureMessage(FQNovelResponse<?> response) {
        if (response == null) {
            return DIRECTORY_FAILURE_PREFIX + "空响应";
        }
        Integer code = response.code();
        String message = Texts.defaultIfBlank(response.message(), "目录接口未返回有效数据");
        String normalizedMessage = "success".equalsIgnoreCase(message) ? "目录接口未返回有效数据" : message;
        return code != null && code != 0
            ? DIRECTORY_FAILURE_PREFIX + "code=" + code + ", message=" + normalizedMessage
            : DIRECTORY_FAILURE_PREFIX + normalizedMessage;
    }



    public CompletableFuture<FQNovelResponse<FQNovelBookInfo>> getBookInfo(String bookId) {
        final String trimmedBookId = Texts.trimToNull(bookId);
        if (trimmedBookId == null) {
            return CompletableFuture.completedFuture(FQNovelResponse.<FQNovelBookInfo>error("书籍ID不能为空"));
        }

        FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
        directoryRequest.setBookId(trimmedBookId);
        directoryRequest.setBookType(0);
        directoryRequest.setNeedVersion(true);
        directoryRequest.setMinimalResponse(false);

        return fqDirectoryService.getBookDirectory(directoryRequest)
            .thenApply(directoryResponse -> {
                if (!RequestCacheHelper.isResponseSuccessWithData(directoryResponse)) {
                    return FQNovelResponse.<FQNovelBookInfo>error(directoryFailureMessage(directoryResponse));
                }
                return buildBookInfoFromDirectoryData(directoryResponse.data(), trimmedBookId);
            })
            .exceptionally(e -> handleBookInfoFailure(trimmedBookId, e));
    }

    private FQNovelResponse<FQNovelBookInfo> buildBookInfoFromDirectoryData(
        FQDirectoryResponse directoryData,
        String bookId
    ) {
        FQNovelBookInfoResp bookInfoResp = directoryData.getBookInfo();
        if (bookInfoResp == null) {
            return FQNovelResponse.error("书籍信息不存在");
        }

        String description = Texts.firstNonBlank(bookInfoResp.getAbstractContent(), bookInfoResp.getBookAbstractV2());
        FQNovelBookInfo bookInfo = new FQNovelBookInfo(
            bookId,
            bookInfoResp.getBookName(),
            bookInfoResp.getAuthor(),
            description,
            bookInfoResp.getThumbUrl(),
            null,
            bookInfoResp.getWordNumber(),
            bookInfoResp.getLastChapterTitle(),
            bookInfoResp.getCategory(),
            Objects.requireNonNullElse(bookInfoResp.getStatus(), 0)
        );
        if (log.isDebugEnabled()) {
            List<FQDirectoryResponse.CatalogItem> catalogData = directoryData.getCatalogData();
            log.debug("调试信息 - bookId: {}, directoryData.serialCount: {}, bookInfoResp.serialCount: {}, directoryData.catalogData.size: {}",
                bookId,
                directoryData.getSerialCount(),
                bookInfoResp.getSerialCount(),
                catalogData == null ? "null" : catalogData.size());
        }

        return FQNovelResponse.success(bookInfo.withTotalChapters(resolveTotalChapters(directoryData, bookInfoResp, bookId)));
    }

    private int resolveTotalChapters(FQDirectoryResponse directoryData, FQNovelBookInfoResp bookInfoResp, String bookId) {
        Integer totalChapters = bookInfoResp.getSerialCount();
        if (totalChapters != null) {
            log.debug("使用bookInfo.serialCount获取章节总数 - bookId: {}, 章节数: {}", bookId, totalChapters);
            return totalChapters;
        }
        totalChapters = directoryData.getSerialCount();
        if (totalChapters != null) {
            log.info("使用目录接口serial_count获取章节总数 - bookId: {}, 章节数: {}", bookId, totalChapters);
            return totalChapters;
        }
        List<FQDirectoryResponse.CatalogItem> catalogData = directoryData.getCatalogData();
        if (catalogData != null && !catalogData.isEmpty()) {
            int catalogSize = catalogData.size();
            log.info("从目录数据获取章节总数 - bookId: {}, 章节数: {}", bookId, catalogSize);
            return catalogSize;
        }
        log.warn("无法获取章节总数 - bookId: {}", bookId);
        return 0;
    }

    private FQNovelResponse<FQNovelBookInfo> handleBookInfoFailure(String bookId, Throwable throwable) {
        Throwable resolved = throwable instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null
            ? ce.getCause()
            : throwable;
        log.error("获取书籍信息失败 - bookId: {}", bookId, resolved);
        String message = resolved == null
            ? "未知错误"
            : Texts.defaultIfBlank(resolved.getMessage(), resolved.toString());
        return FQNovelResponse.error("获取书籍信息失败: " + message);
    }

}
