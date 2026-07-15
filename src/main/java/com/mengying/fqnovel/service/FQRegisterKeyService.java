package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.dto.FqRegisterKeyPayload;
import com.mengying.fqnovel.dto.FqRegisterKeyPayloadResponse;
import com.mengying.fqnovel.dto.FqRegisterKeyResponse;
import com.mengying.fqnovel.utils.FQApiUtils;
import com.mengying.fqnovel.utils.Texts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * FQNovel RegisterKey缓存服务
 * 在启动时获取registerkey并缓存，支持keyver比较和自动刷新
 */
@Service
public class FQRegisterKeyService {

    private static final Logger log = LoggerFactory.getLogger(FQRegisterKeyService.class);
    private static final String REGISTER_KEY_PATH = "/reading/crypt/registerkey";
    private static final String REGISTER_KEY_CONTENT_VERSION = "0";
    private static final long REGISTER_KEY_PAYLOAD_KEYVER = 1L;

    private final FQApiProperties fqApiProperties;
    private final FQApiUtils fqApiUtils;
    private final UpstreamSignedRequestService upstreamSignedRequestService;
    private final ObjectMapper objectMapper;

    public FQRegisterKeyService(
        FQApiProperties fqApiProperties,
        FQApiUtils fqApiUtils,
        UpstreamSignedRequestService upstreamSignedRequestService,
        ObjectMapper objectMapper
    ) {
        this.fqApiProperties = fqApiProperties;
        this.fqApiUtils = fqApiUtils;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
        this.objectMapper = objectMapper;
    }

    // 缓存的registerkey响应，按keyver分组
    private final LinkedHashMap<Long, CacheEntry> cachedRegisterKeys = new LinkedHashMap<Long, CacheEntry>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CacheEntry> eldest) {
            return size() > registerKeyCacheMaxEntries();
        }
    };

    // 当前默认的registerkey响应
    private volatile FqRegisterKeyResponse currentRegisterKey;
    private volatile long currentRegisterKeyExpiresAtMs = 0L;

    /**
     * 获取registerkey，支持keyver比较和自动刷新
     *
     * @param requiredKeyver 需要的keyver，如果为null或<=0则使用当前缓存的key
     * @return RegisterKey响应
     */
    public synchronized FqRegisterKeyResponse getRegisterKey(Long requiredKeyver) throws Exception {
        Long normalizedKeyver = normalizeKeyver(requiredKeyver);

        // 如果没有指定有效keyver，返回当前缓存的key
        if (normalizedKeyver == null) {
            if (requiredKeyver != null) {
                log.debug("收到无效keyver({})，将使用当前缓存的registerkey", requiredKeyver);
            }
            if (isCurrentRegisterKeyValid()) {
                return currentRegisterKey;
            }
            // 如果当前没有缓存的key，获取一个新的
            return refreshRegisterKey();
        }

        // 检查是否已经缓存了指定keyver的key
        FqRegisterKeyResponse cached = getCachedIfPresent(normalizedKeyver);
        if (hasRegisterKey(cached)) {
            log.debug("使用缓存的registerkey，keyver: {}", normalizedKeyver);
            return cached;
        }

        // 如果当前缓存的key的keyver匹配，直接返回
        if (isCurrentRegisterKeyValid() && matchesKeyver(currentRegisterKey, normalizedKeyver)) {
            log.debug("当前registerkey版本匹配，keyver: {}", normalizedKeyver);
            return currentRegisterKey;
        }

        // 不再盲目使用“当前 key”尝试解密：先刷新一次，仍不匹配则明确失败。
        FqRegisterKeyResponse refreshed = refreshRegisterKey();
        Long refreshedKeyver = extractKeyver(refreshed);
        if (matchesKeyver(refreshed, normalizedKeyver)) {
            return refreshed;
        }

        Object mismatchedCurrentKeyver = Objects.requireNonNullElse(refreshedKeyver, "无");
        throw new IllegalStateException("registerkey 版本不匹配 - 当前keyver=" + mismatchedCurrentKeyver
            + "，需要keyver=" + normalizedKeyver + "。已刷新仍不匹配，终止解密");
    }

    private Long normalizeKeyver(Long keyver) {
        if (keyver == null || keyver <= 0) {
            return null;
        }
        return keyver;
    }

    /**
     * 刷新registerkey
     *
     * @return 新的RegisterKey响应
     */
    public synchronized FqRegisterKeyResponse refreshRegisterKey() throws Exception {
        log.debug("刷新 registerkey...");
        FqRegisterKeyResponse response = fetchRegisterKey();
        long keyver = validateRefreshedRegisterKey(response);

        long expiresAt = putCache(keyver, response);
        currentRegisterKey = response;
        currentRegisterKeyExpiresAtMs = expiresAt;
        FQEncryptServiceWorker.recordUpstreamSuccess();
        log.debug("registerkey 刷新成功：keyver={}", keyver);
        return response;
    }

    private long validateRefreshedRegisterKey(FqRegisterKeyResponse response) {
        if (response == null) {
            throw new IllegalStateException("刷新registerkey失败，响应为空");
        }

        if (response.code() != 0) {
            throw new IllegalStateException("刷新registerkey失败，上游返回 code=" + response.code()
                + ", message=" + response.message());
        }

        FqRegisterKeyPayloadResponse data = response.data();
        if (data == null || !Texts.hasText(data.key())) {
            throw new IllegalStateException("刷新registerkey失败，响应缺少有效key");
        }

        long keyver = data.keyver();
        if (keyver <= 0) {
            throw new IllegalStateException("刷新registerkey失败，响应缺少有效keyver");
        }

        return keyver;
    }

    /**
     * 实际获取registerkey的方法
     *
     * @return RegisterKey响应
     */
    private FqRegisterKeyResponse fetchRegisterKey() throws Exception {
        // 使用工具类构建URL和参数
        String url = fqApiUtils.getBaseUrl() + REGISTER_KEY_PATH;
        Map<String, String> params = fqApiUtils.buildCommonApiParams();
        String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

        // 生成统一的时间戳
        long currentTime = System.currentTimeMillis();

        // 使用工具类构建请求头
        Map<String, String> headers = fqApiUtils.buildRegisterKeyHeaders(currentTime);

        // 创建请求载荷
        FqRegisterKeyPayload payload = buildRegisterKeyPayload();

        log.debug("发送registerkey请求到: {}", fullUrl);
        log.debug("请求时间戳: {}", currentTime);
        log.debug("请求载荷: content={}, keyver={}", payload.content(), payload.keyver());

        UpstreamSignedRequestService.UpstreamJsonResult upstream =
            upstreamSignedRequestService.executeSignedJsonPost(fullUrl, headers, payload);
        if (upstream == null) {
            throw new IllegalStateException("签名生成失败，无法请求 registerkey");
        }

        String responseSnippet = upstream.responseSnippet();
        if (log.isDebugEnabled()) {
            log.debug("registerkey原始响应: {}", Texts.nullToEmpty(responseSnippet));
        }

        JsonNode root = upstream.jsonBody();
        FqRegisterKeyResponse parsed = objectMapper.treeToValue(root, FqRegisterKeyResponse.class);

        if (parsed == null) {
            throw new IllegalStateException("registerkey 响应解析失败: body为空");
        }

        log.debug("registerkey请求响应: code={}, message={}, keyver={}",
            parsed.code(), parsed.message(),
            Objects.requireNonNullElse(extractKeyver(parsed), "null"));

        return parsed;
    }

    /**
     * 获取指定keyver的解密密钥
     *
     * @param requiredKeyver 需要的keyver
     * @return 解密密钥（十六进制字符串）
     */
    public String getDecryptionKey(Long requiredKeyver) throws Exception {
        FqRegisterKeyResponse registerKeyResponse = getRegisterKey(requiredKeyver);
        if (registerKeyResponse.data() == null) {
            throw new IllegalStateException("registerkey 响应 data 为空");
        }
        return FqCrypto.getRealKey(registerKeyResponse.data().key());
    }

    private FqRegisterKeyPayload buildRegisterKeyPayload() throws Exception {
        FqCrypto crypto = new FqCrypto(FqCrypto.REG_KEY);
        String content = crypto.newRegisterKeyContent(fqApiUtils.getServerDeviceId(), REGISTER_KEY_CONTENT_VERSION);
        return new FqRegisterKeyPayload(content, REGISTER_KEY_PAYLOAD_KEYVER);
    }

    /**
     * 仅使当前默认key失效，保留历史 keyver 缓存（避免设备切换后丢失可复用 key）。
     */
    public void invalidateCurrentKey() {
        synchronized (this) {
            clearCurrentKeyLocked();
        }
        if (log.isDebugEnabled()) {
            log.debug("registerkey当前键已失效");
        }
    }

    /**
     * 获取缓存状态信息
     */
    public Map<String, Object> getCacheStatus() {
        synchronized (this) {
            Map<String, Object> status = new HashMap<>();
            status.put("cachedKeyversCount", cachedRegisterKeys.size());
            status.put("cachedKeyvers", new java.util.ArrayList<>(cachedRegisterKeys.keySet()));
            status.put("currentKeyver", extractKeyver(currentRegisterKey));
            status.put("cacheMaxEntries", registerKeyCacheMaxEntries());
            status.put("cacheTtlMs", registerKeyCacheTtlMs());
            return status;
        }
    }

    private FqRegisterKeyResponse getCachedIfPresent(Long keyver) {
        CacheEntry entry = cachedRegisterKeys.get(keyver);
        if (entry == null) {
            return null;
        }
        if (isCacheEntryExpired(entry)) {
            cachedRegisterKeys.remove(keyver);
            return null;
        }
        return entry.value;
    }

    private long putCache(long keyver, FqRegisterKeyResponse value) {
        long expiresAt = computeExpiresAt(System.currentTimeMillis());
        cachedRegisterKeys.put(keyver, new CacheEntry(value, expiresAt));
        return expiresAt;
    }

    private boolean isCurrentRegisterKeyValid() {
        if (currentRegisterKey == null || currentRegisterKey.data() == null) {
            return false;
        }
        if (isExpired(currentRegisterKeyExpiresAtMs)) {
            clearCurrentKeyLocked();
            return false;
        }
        return true;
    }

    private void clearCurrentKeyLocked() {
        currentRegisterKey = null;
        currentRegisterKeyExpiresAtMs = 0L;
    }

    private long computeExpiresAt(long nowMs) {
        long ttlMs = registerKeyCacheTtlMs();
        return ttlMs <= 0 ? Long.MAX_VALUE : (nowMs + ttlMs);
    }

    private int registerKeyCacheMaxEntries() {
        return Math.max(1, fqApiProperties.getRegisterKeyCacheMaxEntries());
    }

    private long registerKeyCacheTtlMs() {
        return Math.max(0L, fqApiProperties.getRegisterKeyCacheTtlMs());
    }

    private boolean isExpired(long expiresAtMs) {
        return expiresAtMs > 0L && expiresAtMs < System.currentTimeMillis();
    }

    private boolean isCacheEntryExpired(CacheEntry entry) {
        if (entry == null) {
            return true;
        }
        long ttlMs = registerKeyCacheTtlMs();
        return ttlMs > 0 && entry.expiresAtMs < System.currentTimeMillis();
    }

    private static Long extractKeyver(FqRegisterKeyResponse response) {
        if (response == null || response.data() == null) {
            return null;
        }
        return response.data().keyver();
    }

    private static boolean hasRegisterKey(FqRegisterKeyResponse response) {
        return extractKeyver(response) != null;
    }

    private static boolean matchesKeyver(FqRegisterKeyResponse response, Long expectedKeyver) {
        if (expectedKeyver == null) {
            return false;
        }
        Long actualKeyver = extractKeyver(response);
        return actualKeyver != null && actualKeyver.longValue() == expectedKeyver.longValue();
    }

    private static final class CacheEntry {
        private final FqRegisterKeyResponse value;
        private final long expiresAtMs;

        private CacheEntry(FqRegisterKeyResponse value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
