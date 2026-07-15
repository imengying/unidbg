package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.config.FQConstants;
import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQDirectoryResponse;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.utils.FQApiUtils;
import com.mengying.fqnovel.utils.FQDirectoryResponseTransformer;
import com.mengying.fqnovel.utils.LocalCacheFactory;
import com.mengying.fqnovel.utils.Texts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 书籍目录服务（从 FQSearchService 拆分而来）。
 * <p>
 * 负责调用上游目录接口、解析响应、管理缓存和请求去重。
 */
@Service
public class FQDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(FQDirectoryService.class);

    private final FQApiUtils fqApiUtils;
    private final FQDownloadProperties downloadProperties;
    private final ObjectMapper objectMapper;
    private final UpstreamSignedRequestService upstreamSignedRequestService;
    @Qualifier("applicationTaskExecutor")
    private final Executor taskExecutor;

    private Cache<String, FQDirectoryResponse> directoryApiCache;
    private final ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<FQDirectoryResponse>>> inflightDirectory = new ConcurrentHashMap<>();

    public FQDirectoryService(
        FQApiUtils fqApiUtils,
        FQDownloadProperties downloadProperties,
        ObjectMapper objectMapper,
        UpstreamSignedRequestService upstreamSignedRequestService,
        @Qualifier("applicationTaskExecutor") Executor taskExecutor
    ) {
        this.fqApiUtils = fqApiUtils;
        this.downloadProperties = downloadProperties;
        this.objectMapper = objectMapper;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
        this.taskExecutor = taskExecutor;
    }

    @PostConstruct
    public void initCaches() {
        int directoryMax = Math.max(1, downloadProperties.getCache().getApiDirectoryMaxEntries());
        long directoryTtl = Math.max(0L, downloadProperties.getCache().getApiDirectoryTtlMs());
        this.directoryApiCache = LocalCacheFactory.build(directoryMax, directoryTtl);
    }

    // ── 公开方法 ─────────────────────────────────────────────────

    public CompletableFuture<FQNovelResponse<FQDirectoryResponse>> getBookDirectory(FQDirectoryRequest directoryRequest) {
        CompletableFuture<FQNovelResponse<FQDirectoryResponse>> shuttingDown = RequestCacheHelper.completedShuttingDownIfNeeded();
        if (shuttingDown != null) {
            return shuttingDown;
        }

        String cacheKey = buildDirectoryCacheKey(directoryRequest);
        return RequestCacheHelper.loadWithRequestCache(
            cacheKey,
            directoryApiCache,
            inflightDirectory,
            () -> getBookDirectoryInternal(directoryRequest),
            RequestCacheHelper::isResponseSuccessWithData,
            null,
            "目录",
            taskExecutor
        );
    }

    // ── 内部实现 ─────────────────────────────────────────────────

    private FQNovelResponse<FQDirectoryResponse> getBookDirectoryInternal(FQDirectoryRequest directoryRequest) {
        try {
            FQNovelResponse<FQDirectoryResponse> shuttingDown = RequestCacheHelper.shuttingDownIfNeeded();
            if (shuttingDown != null) {
                return shuttingDown;
            }

            if (directoryRequest == null) {
                return FQNovelResponse.error("目录请求不能为空");
            }

            String url = fqApiUtils.getSearchApiBaseUrl() + FQConstants.Search.DIRECTORY_ALL_ITEMS_PATH;
            Map<String, String> params = fqApiUtils.buildDirectoryParams(directoryRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            UpstreamSignedRequestService.UpstreamJsonResult upstream = upstreamSignedRequestService.executeSignedJsonGetOrLogFailure(
                fullUrl,
                fqApiUtils.buildCommonHeaders(),
                "目录请求",
                log
            );
            if (upstream == null) {
                return FQNovelResponse.error("签名生成失败");
            }

            String responseSnippet = upstream.responseSnippet();
            JsonNode rootNode = upstream.jsonBody();
            Integer upstreamCode = UpstreamSignedRequestService.nonZeroUpstreamCode(rootNode);
            if (upstreamCode != null) {
                String upstreamMessage = UpstreamSignedRequestService.upstreamMessageOrDefault(rootNode, "upstream error");
                UpstreamSignedRequestService.logUpstreamBodyDebug(log, "目录接口上游失败原始响应", responseSnippet);
                return FQNovelResponse.error(upstreamCode, upstreamMessage);
            }

            JsonNode dataNode = rootNode.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                UpstreamSignedRequestService.logUpstreamBodyDebug(log, "目录接口上游缺少data原始响应", responseSnippet);
                return directoryFailure(UpstreamSignedRequestService.upstreamMessageOrDefault(rootNode, "upstream response missing data"));
            }

            FQDirectoryResponse directoryResponse = objectMapper.treeToValue(dataNode, FQDirectoryResponse.class);
            if (directoryResponse == null) {
                return directoryFailure(UpstreamSignedRequestService.upstreamMessageOrDefault(rootNode, "upstream parse error"));
            }
            if (Boolean.TRUE.equals(directoryRequest.getMinimalResponse())) {
                FQDirectoryResponseTransformer.trimForMinimalResponse(directoryResponse);
            } else {
                FQDirectoryResponseTransformer.enhanceChapterList(directoryResponse);
            }
            FQEncryptServiceWorker.recordUpstreamSuccess();
            return FQNovelResponse.success(directoryResponse);

        } catch (Exception e) {
            String bookId = directoryRequest == null ? null : directoryRequest.getBookId();
            log.error("获取书籍目录失败 - bookId: {}", bookId, e);
            return directoryFailure(e.getMessage());
        }
    }

    // ── 辅助方法 ─────────────────────────────────────────────────

    private static String buildDirectoryCacheKey(FQDirectoryRequest request) {
        if (request == null) {
            return null;
        }
        String bookId = Texts.trimToEmpty(request.getBookId());
        if (bookId.isEmpty()) {
            return null;
        }
        int bookType = request.getBookType() != null ? request.getBookType() : 0;
        boolean minimalResponse = Boolean.TRUE.equals(request.getMinimalResponse());
        boolean needVersion = !minimalResponse && (request.getNeedVersion() == null || request.getNeedVersion());
        String itemMd5 = Texts.trimToEmpty(request.getItemDataListMd5());
        String catalogMd5 = Texts.trimToEmpty(request.getCatalogDataMd5());
        String bookInfoMd5 = Texts.trimToEmpty(request.getBookInfoMd5());
        return bookId + "|" + bookType + "|" + needVersion + "|" + minimalResponse + "|" + itemMd5 + "|" + catalogMd5 + "|" + bookInfoMd5;
    }

    private static FQNovelResponse<FQDirectoryResponse> directoryFailure(String reason) {
        return FQNovelResponse.error("获取书籍目录失败: " + reason);
    }
}
