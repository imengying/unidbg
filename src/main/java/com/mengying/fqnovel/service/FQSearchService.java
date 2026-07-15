package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.config.FQConstants;
import com.mengying.fqnovel.utils.ThrottledLogger;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.dto.FQSearchRequest;
import com.mengying.fqnovel.dto.FQSearchResponse;
import com.mengying.fqnovel.dto.FQSearchUpstreamRequest;
import com.mengying.fqnovel.utils.FQApiUtils;
import com.mengying.fqnovel.utils.FQSearchResponseParser;
import com.mengying.fqnovel.utils.LocalCacheFactory;
import com.mengying.fqnovel.utils.RetryBackoff;
import com.mengying.fqnovel.utils.Texts;
import com.github.benmanes.caffeine.cache.Cache;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class FQSearchService {

    private static final Logger log = LoggerFactory.getLogger(FQSearchService.class);
    private static final String REASON_SEARCH_WITH_ID_FAIL = "SEARCH_WITH_ID_FAIL";
    private static final String REASON_SEARCH_PHASE1_FAIL = "SEARCH_PHASE1_FAIL";
    private static final String REASON_SEARCH_NO_SEARCH_ID = "SEARCH_NO_SEARCH_ID";
    private static final String REASON_SEARCH_PHASE2_FAIL = "SEARCH_PHASE2_FAIL";
    private static final String REASON_SEARCH_EXCEPTION = "SEARCH_EXCEPTION";
    private static final String ERROR_NO_SEARCH_ID =
        "上游未返回search_id（可能风控/上游异常），请稍后重试";
    private static final int RETRY_MAX_BACKOFF_EXPONENT = 10;
    private static final long RETRY_JITTER_MIN_MS = 150L;
    private static final long RETRY_JITTER_MAX_MS = 450L;
    private static final int SEARCH_ID_DEBUG_SNIPPET_LENGTH = 1200;

    private final FQApiProperties fqApiProperties;
    private final FQApiUtils fqApiUtils;
    private final FQDeviceRotationService deviceRotationService;
    private final AutoRestartService autoRestartService;
    private final FQDownloadProperties downloadProperties;
    private final FQSearchRequestEnricher searchRequestEnricher;
    private final UpstreamSignedRequestService upstreamSignedRequestService;
    @Qualifier("applicationTaskExecutor")
    private final Executor taskExecutor;

    private Cache<String, FQSearchResponse> searchCache;
    private final ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<FQSearchResponse>>> inflightSearch = new ConcurrentHashMap<>();
    private final ThrottledLogger throttledLog = new ThrottledLogger(60_000L);

    public FQSearchService(
        FQApiProperties fqApiProperties,
        FQApiUtils fqApiUtils,
        FQDeviceRotationService deviceRotationService,
        AutoRestartService autoRestartService,
        FQDownloadProperties downloadProperties,
        FQSearchRequestEnricher searchRequestEnricher,
        UpstreamSignedRequestService upstreamSignedRequestService,
        @Qualifier("applicationTaskExecutor") Executor taskExecutor
    ) {
        this.fqApiProperties = fqApiProperties;
        this.fqApiUtils = fqApiUtils;
        this.deviceRotationService = deviceRotationService;
        this.autoRestartService = autoRestartService;
        this.downloadProperties = downloadProperties;
        this.searchRequestEnricher = searchRequestEnricher;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
        this.taskExecutor = taskExecutor;
    }

    @PostConstruct
    public void initCaches() {
        int searchMax = Math.max(1, downloadProperties.getCache().getSearchMaxEntries());
        long searchTtl = Math.max(0L, downloadProperties.getCache().getSearchTtlMs());
        this.searchCache = LocalCacheFactory.build(searchMax, searchTtl);
    }

    private static String normalizeCachePart(String value) {
        return Texts.trimToEmpty(value);
    }

    private static int intOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static String buildSearchCacheKey(FQSearchRequest request) {
        if (request == null) {
            return null;
        }
        String query = normalizeCachePart(request.getQuery());
        if (query.isEmpty()) {
            return null;
        }
        int offset = intOrDefault(request.getOffset(), 0);
        int count = intOrDefault(request.getCount(), 20);
        int tabType = intOrDefault(request.getTabType(), 1);
        String searchId = normalizeCachePart(request.getSearchId());
        return query + "|" + offset + "|" + count + "|" + tabType + "|" + searchId;
    }

    private static String extractSearchId(FQNovelResponse<FQSearchResponse> response) {
        return response != null && response.data() != null ? Texts.trimToEmpty(response.data().getSearchId()) : "";
    }

    private static boolean hasBooks(FQNovelResponse<FQSearchResponse> response) {
        List<FQSearchResponse.BookItem> books = response == null || response.data() == null ? null : response.data().getBooks();
        return books != null && !books.isEmpty();
    }

    private static <T> FQNovelResponse<T> signerFailResponse() {
        return FQNovelResponse.error("签名生成失败");
    }

    private void recordSearchOutcome(FQNovelResponse<?> response, String failureReason) {
        if (RequestCacheHelper.isResponseSuccess(response)) {
            FQEncryptServiceWorker.recordUpstreamSuccess();
            autoRestartService.recordSuccess();
        } else {
            autoRestartService.recordFailure(failureReason);
        }
    }



    public CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooksEnhanced(FQSearchRequest searchRequest) {
        CompletableFuture<FQNovelResponse<FQSearchResponse>> shuttingDown = RequestCacheHelper.completedShuttingDownIfNeeded();
        if (shuttingDown != null) {
            return shuttingDown;
        }

        String cacheKey = buildSearchCacheKey(searchRequest);
        return RequestCacheHelper.loadWithRequestCacheAsync(
            cacheKey,
            searchCache,
            inflightSearch,
            () -> searchBooksEnhancedInternalAsync(searchRequest),
            RequestCacheHelper::isResponseSuccessWithData,
            null,
            "搜索"
        );
    }

    private CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooksEnhancedInternalAsync(FQSearchRequest searchRequest) {
        return CompletableFuture.supplyAsync(() -> prepareSearchContinuation(searchRequest), taskExecutor)
            .thenCompose(continuation -> {
                if (continuation.response() != null) {
                    return CompletableFuture.completedFuture(continuation.response());
                }

                long delay = ThreadLocalRandom.current().nextLong(
                    FQConstants.Search.MIN_SEARCH_DELAY_MS,
                    FQConstants.Search.MAX_SEARCH_DELAY_MS + 1
                );
                int lastSearchPageInterval = (int) delay;
                Executor delayedExecutor = CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, taskExecutor);
                return CompletableFuture.supplyAsync(
                    () -> executeSecondPhaseSearch(continuation.enrichedRequest(), continuation.searchId(), lastSearchPageInterval),
                    delayedExecutor
                );
            })
            .exceptionally(e -> {
                Throwable cause = e instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null
                    ? ce.getCause()
                    : e;
                String query = searchRequest == null ? null : searchRequest.getQuery();
                log.error("增强搜索失败 - query: {}", query, cause);
                autoRestartService.recordFailure(REASON_SEARCH_EXCEPTION);
                String message = cause == null ? "未知错误" : Texts.defaultIfBlank(cause.getMessage(), cause.toString());
                return FQNovelResponse.error("增强搜索失败: " + message);
            });
    }

    private SearchContinuation prepareSearchContinuation(FQSearchRequest searchRequest) {
        try {
            FQNovelResponse<FQSearchResponse> shuttingDown = RequestCacheHelper.shuttingDownIfNeeded();
            if (shuttingDown != null) {
                return SearchContinuation.done(shuttingDown);
            }

            if (searchRequest == null) {
                return SearchContinuation.done(FQNovelResponse.error("搜索请求不能为空"));
            }
            FQSearchUpstreamRequest enrichedRequest = searchRequestEnricher.enrich(searchRequest);

            if (Texts.hasText(enrichedRequest.getSearchId())) {
                enrichedRequest.setIsFirstEnterSearch(false);
                FQNovelResponse<FQSearchResponse> response = performSearchInternal(enrichedRequest);
                recordSearchOutcome(response, REASON_SEARCH_WITH_ID_FAIL);
                return SearchContinuation.done(response);
            }

            FQSearchUpstreamRequest firstRequest = copyRequest(enrichedRequest);
            firstRequest.setIsFirstEnterSearch(true);
            firstRequest.setLastSearchPageInterval(FQConstants.Search.PHASE1_LAST_SEARCH_PAGE_INTERVAL);
            FQNovelResponse<FQSearchResponse> firstResponse = performSearchInternal(firstRequest);
            if (!RequestCacheHelper.isResponseSuccess(firstResponse)
                && UpstreamSignedRequestService.isLikelyRiskControl(firstResponse.message())
                && deviceRotationService.rotateIfNeeded(REASON_SEARCH_PHASE1_FAIL)) {
                firstResponse = performSearchInternal(firstRequest);
            }
            if (!RequestCacheHelper.isResponseSuccess(firstResponse)) {
                if (throttledLog.shouldLog("search.phase1.fail")) {
                    log.warn("第一阶段搜索失败 - code: {}, message: {}",
                        firstResponse.code(), firstResponse.message());
                }
                autoRestartService.recordFailure(REASON_SEARCH_PHASE1_FAIL);
                return SearchContinuation.done(firstResponse);
            }

            String searchId = extractSearchId(firstResponse);
            if (Texts.isBlank(searchId) && hasBooks(firstResponse)) {
                log.info("第一阶段未返回search_id，但已返回书籍结果，跳过第二阶段");
                autoRestartService.recordSuccess();
                return SearchContinuation.done(firstResponse);
            }
            if (Texts.isBlank(searchId)) {
                int perDeviceRetries = Math.max(
                    1,
                    Math.min(FQConstants.Search.MAX_RETRIES_PER_DEVICE, downloadProperties.getRetry().getMaxRetries())
                );
                List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
                int maxDevices = Math.max(1, pool == null ? 0 : pool.size());
                FQNovelResponse<FQSearchResponse> candidate = firstResponse;

                searchRetry:
                for (int deviceAttempt = 0; deviceAttempt < maxDevices; deviceAttempt++) {
                    if (deviceAttempt > 0 && !deviceRotationService.forceRotate(REASON_SEARCH_NO_SEARCH_ID)) {
                        break;
                    }
                    for (int retryAttempt = 0; retryAttempt < perDeviceRetries; retryAttempt++) {
                        if (!(deviceAttempt == 0 && retryAttempt == 0)) {
                            int retryOrdinal = deviceAttempt * perDeviceRetries + retryAttempt + 1;
                            long delay = RetryBackoff.computeDelay(
                                downloadProperties.getRetry().getDelayMs(),
                                downloadProperties.getRetry().getMaxDelayMs(),
                                retryOrdinal,
                                RETRY_MAX_BACKOFF_EXPONENT,
                                RETRY_JITTER_MIN_MS,
                                RETRY_JITTER_MAX_MS
                            );
                            if (!RetryBackoff.sleep(delay)) {
                                autoRestartService.recordFailure(REASON_SEARCH_EXCEPTION);
                                return SearchContinuation.done(FQNovelResponse.error("搜索流程被中断，请稍后重试"));
                            }
                            candidate = performSearchInternal(firstRequest);
                        }

                        searchId = extractSearchId(candidate);
                        if (Texts.hasText(searchId)) {
                            break searchRetry;
                        }
                        if (hasBooks(candidate)) {
                            log.info("第一阶段未返回search_id，但重试后已返回书籍结果，跳过第二阶段");
                            autoRestartService.recordSuccess();
                            return SearchContinuation.done(candidate);
                        }
                    }
                }
            }
            if (Texts.isBlank(searchId)) {
                if (throttledLog.shouldLog("search.no_search_id")) {
                    log.warn("第一阶段搜索未返回search_id（可能风控/上游异常），建议稍后重试");
                }
                autoRestartService.recordFailure(REASON_SEARCH_NO_SEARCH_ID);
                return SearchContinuation.done(FQNovelResponse.error(ERROR_NO_SEARCH_ID));
            }

            return SearchContinuation.next(enrichedRequest, searchId);

        } catch (Exception e) {
            String query = searchRequest == null ? null : searchRequest.getQuery();
            log.error("增强搜索失败 - query: {}", query, e);
            autoRestartService.recordFailure(REASON_SEARCH_EXCEPTION);
            return SearchContinuation.done(FQNovelResponse.error("增强搜索失败: " + e.getMessage()));
        }
    }

    private FQNovelResponse<FQSearchResponse> executeSecondPhaseSearch(
        FQSearchUpstreamRequest enrichedRequest,
        String searchId,
        int lastSearchPageInterval
    ) {
        FQNovelResponse<FQSearchResponse> shuttingDown = RequestCacheHelper.shuttingDownIfNeeded();
        if (shuttingDown != null) {
            return shuttingDown;
        }

        FQSearchUpstreamRequest secondRequest = copyRequest(enrichedRequest);
        secondRequest.setSearchId(searchId);
        secondRequest.setIsFirstEnterSearch(false);
        secondRequest.setLastSearchPageInterval(lastSearchPageInterval);

        FQNovelResponse<FQSearchResponse> secondResponse = performSearchInternal(secondRequest);
        if (secondResponse.code() != null
            && secondResponse.code() == 0
            && secondResponse.data() != null) {
            secondResponse.data().setSearchId(searchId);
        }
        recordSearchOutcome(secondResponse, REASON_SEARCH_PHASE2_FAIL);
        return secondResponse;
    }

    private record SearchContinuation(
        FQSearchUpstreamRequest enrichedRequest,
        String searchId,
        FQNovelResponse<FQSearchResponse> response
    ) {
        private static SearchContinuation next(FQSearchUpstreamRequest enrichedRequest, String searchId) {
            return new SearchContinuation(enrichedRequest, searchId, null);
        }

        private static SearchContinuation done(FQNovelResponse<FQSearchResponse> response) {
            return new SearchContinuation(null, null, response);
        }
    }

    private static void ensurePassback(FQSearchUpstreamRequest request) {
        if (request != null && request.getPassback() == null) {
            request.setPassback(request.getOffset());
        }
    }

    private static FQSearchUpstreamRequest copyRequest(FQSearchUpstreamRequest request) {
        FQSearchUpstreamRequest copied = request == null ? new FQSearchUpstreamRequest() : request.copy();
        ensurePassback(copied);
        return copied;
    }

    private FQNovelResponse<FQSearchResponse> performSearchInternal(FQSearchUpstreamRequest searchRequest) {
        try {
            String url = fqApiUtils.getSearchApiBaseUrl() + FQConstants.Search.TAB_PATH;
            Map<String, String> params = fqApiUtils.buildSearchParams(searchRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            UpstreamSignedRequestService.UpstreamJsonResult upstream = upstreamSignedRequestService.executeSignedJsonGetOrLogFailure(
                fullUrl,
                fqApiUtils.buildSearchHeaders(),
                "请求",
                log
            );
            if (upstream == null) {
                return signerFailResponse();
            }

            String responseSnippet = upstream.responseSnippet();
            JsonNode jsonResponse = upstream.jsonBody();

            Integer upstreamCode = UpstreamSignedRequestService.nonZeroUpstreamCode(jsonResponse);
            if (upstreamCode != null) {
                String upstreamMessage = UpstreamSignedRequestService.upstreamMessageOrDefault(jsonResponse, "upstream error");
                log.warn("上游搜索接口返回失败 - code: {}, message: {}", upstreamCode, upstreamMessage);
                return FQNovelResponse.error(upstreamCode, upstreamMessage);
            }

            int tabType = intOrDefault(searchRequest.getTabType(), 1);
            FQSearchResponse searchResponse = FQSearchResponseParser.parseSearchResponse(jsonResponse, tabType);
            if (searchResponse == null) {
                UpstreamSignedRequestService.logUpstreamBodyDebug(log, "搜索接口解析失败原始响应", responseSnippet);
                return FQNovelResponse.error("搜索响应解析失败");
            }

            if (Texts.isBlank(searchResponse.getSearchId())) {
                String fromBody = FQSearchResponseParser.deepFindSearchId(jsonResponse);
                if (Texts.hasText(fromBody)) {
                    searchResponse.setSearchId(fromBody);
                }
                if (Texts.isBlank(searchResponse.getSearchId())) {
                    String fromHeader = upstream.searchIdHeader();
                    if (Texts.hasText(fromHeader)) {
                        searchResponse.setSearchId(fromHeader);
                    }
                }
            }
            if (Boolean.TRUE.equals(searchRequest.getIsFirstEnterSearch())
                && Texts.isBlank(searchResponse.getSearchId())
                && log.isDebugEnabled()) {
                log.debug("第一阶段搜索未返回search_id，原始响应: {}",
                    Texts.truncate(responseSnippet, SEARCH_ID_DEBUG_SNIPPET_LENGTH));
            }

            return FQNovelResponse.success(searchResponse);

        } catch (Exception e) {
            log.error("搜索请求失败 - query: {}", searchRequest == null ? null : searchRequest.getQuery(), e);
            return FQNovelResponse.error("搜索请求失败: " + e.getMessage());
        }
    }

}
