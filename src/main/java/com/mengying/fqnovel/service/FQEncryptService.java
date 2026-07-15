package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.UnidbgProperties;
import com.mengying.fqnovel.unidbg.IdleFQ;
import com.mengying.fqnovel.utils.ProcessLifecycle;
import com.mengying.fqnovel.utils.TempFileUtils;
import com.mengying.fqnovel.utils.Texts;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class FQEncryptService {

    private static final Logger log = LoggerFactory.getLogger(FQEncryptService.class);
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern HEADER_COLON_PAIR = Pattern.compile("^[A-Za-z0-9-]{1,64}:\\s*.+$");
    private static final String HEADER_X_ARGUS = "x-argus";
    private static final String HEADER_X_GORGON = "x-gorgon";
    private static final int RAW_LOG_MAX_LENGTH = 200;

    private final UnidbgProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile IdleFQ idleFQ;

    public FQEncryptService(UnidbgProperties properties) {
        this.properties = properties;
        this.idleFQ = createIdleFq();
        log.info("签名服务初始化完成");
    }

    public void reset(String reason) {
        if (ProcessLifecycle.isShuttingDown()) {
            log.warn("进程退出中，跳过签名服务重置: reason={}", reason);
            return;
        }
        lock.lock();
        try {
            IdleFQ old = this.idleFQ;
            IdleFQ replacement;
            boolean oldDestroyedForRetry = false;

            try {
                replacement = createIdleFq();
            } catch (OutOfMemoryError oom) {
                // 在容器内存偏紧时，先销毁旧实例再重试一次，避免短时间双 signer 共存放大内存峰值。
                log.warn("重置签名服务时创建新实例内存不足，先释放旧实例后重试: reason={}", reason, oom);
                this.idleFQ = null;
                destroySignerQuietly(old);
                oldDestroyedForRetry = true;
                replacement = createIdleFq();
            }

            this.idleFQ = replacement;
            if (!oldDestroyedForRetry) {
                destroySignerQuietly(old);
            }
            log.warn("签名服务已重置，reason={}", reason);
        } finally {
            lock.unlock();
        }
    }

    private IdleFQ createIdleFq() {
        return new IdleFQ(properties.isVerbose(), properties.getApkPath(), properties.getApkClasspath());
    }

    /**
     * 生成FQ应用的签名headers
     *
     * @param url 请求的URL
     * @param headers 请求头信息，格式为key\r\nvalue\r\n的字符串
     * @return 包含各种签名header的Map
     */
    public Map<String, String> generateSignatureHeaders(String url, String headers) {
        try {
            if (ProcessLifecycle.isShuttingDown()) {
                return Map.of();
            }

            if (log.isDebugEnabled()) {
                log.debug("准备生成FQ签名 - URL: {}", url);
                log.debug("准备生成FQ签名 - Headers: {}", maskSensitiveHeaders(headers));
            }

            String signatureResult;
            lock.lock();
            try {
                IdleFQ signer = ensureSignerLocked();
                signatureResult = signer.generateSignature(url, headers);
            } finally {
                lock.unlock();
            }

            if (!Texts.hasText(signatureResult)) {
                log.error("签名生成失败，返回结果为空");
                return Map.of();
            }

            // 解析返回的签名结果
            Map<String, String> result = parseSignatureResult(signatureResult);

            removeHeaderIgnoreCase(result, "X-Neptune");

            if (log.isDebugEnabled()) {
                log.debug("FQ签名生成成功: {}", result);
            }
            return result;

        } catch (Exception e) {
            log.error("生成FQ签名失败", e);
            return Map.of();
        }
    }

    private IdleFQ ensureSignerLocked() {
        IdleFQ signer = this.idleFQ;
        if (signer != null) {
            return signer;
        }
        this.idleFQ = createIdleFq();
        return this.idleFQ;
    }

    /**
     * 生成FQ应用的签名headers (重载方法，支持Map格式的headers)
     *
     * @param url 请求的URL
     * @param headerMap 请求头的Map，key为header名称，value为header值
     * @return 包含各种签名header的Map
     */
    public Map<String, String> generateSignatureHeaders(String url, Map<String, String> headerMap) {
        if (headerMap == null || headerMap.isEmpty()) {
            return generateSignatureHeaders(url, "");
        }

        return generateSignatureHeaders(url, buildSignatureInputHeaders(headerMap));
    }

    /**
     * 解析签名生成结果
     * 根据返回的字符串解析出各个header值
     */
    private Map<String, String> parseSignatureResult(String signatureResult) {
        if (signatureResult == null) {
            return Map.of();
        }

        String normalized = Texts.trimToEmpty(normalizeLineBreaks(signatureResult));
        if (normalized.isEmpty()) {
            return Map.of();
        }

        // 1) JSON 格式：{"X-Argus":"...","X-Khronos":"..."}
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            try {
                Map<String, String> jsonMap = SHARED_OBJECT_MAPPER.readValue(normalized, new TypeReference<Map<String, String>>() {});
                return jsonMap != null ? new HashMap<>(jsonMap) : Map.of();
            } catch (Exception ignored) {
                // 继续按行格式解析
            }
        }

        // 2) 行格式：支持
        //    - key\nvalue\nkey\nvalue...
        //    - key: value\nkey2: value2...
        String[] lines = normalized.split("\n");
        Map<String, String> result = new HashMap<>();

        if (looksLikeColonPairs(lines)) {
            parseColonPairs(lines, result);
        } else if (lines.length >= 2 && lines.length % 2 == 0) {
            parseAlternatingPairs(lines, result);
        } else {
            // 兜底：尝试按空白分隔的 key=value
            parseEqualsPairs(lines, result);
        }

        // 常见签名头部可能存在大小写差异，这里仅做存在性提示，不做强制
        boolean hasArgus = hasCommonSignatureHeader(result);
        if (!hasArgus) {
            log.warn("签名结果解析后未发现常见签名头部，raw={}", truncateForLog(normalized));
        }

        return result;
    }

    private String maskSensitiveHeaders(String headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        String normalized = normalizeLineBreaks(headers);
        StringBuilder masked = new StringBuilder();
        String[] lines = normalized.split("\n");
        boolean redactNextValue = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = Texts.trimToEmpty(line);
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (redactNextValue) {
                masked.append("[REDACTED]");
                redactNextValue = false;
            } else if (isSensitiveHeaderName(lower)) {
                int idx = line.indexOf(':');
                if (idx > -1) {
                    String name = line.substring(0, idx + 1);
                    masked.append(name).append(" [REDACTED]");
                } else {
                    masked.append(line);
                    redactNextValue = true;
                }
            } else {
                masked.append(line);
            }
            if (i < lines.length - 1) {
                masked.append('\n');
            }
        }
        return masked.toString();
    }

    private boolean isSensitiveHeaderName(String lowerCaseName) {
        return lowerCaseName.startsWith("authorization")
            || lowerCaseName.startsWith("cookie")
            || lowerCaseName.startsWith("x-tt-argon")
            || lowerCaseName.startsWith("x-tt-uuid");
    }

    private void removeHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty() || name == null) {
            return;
        }
        String target = name.toLowerCase(Locale.ROOT);
        headers.keySet().removeIf(key -> key != null && key.toLowerCase(Locale.ROOT).equals(target));
    }

    private static String normalizeLineBreaks(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean looksLikeColonPairs(String[] lines) {
        for (String line : lines) {
            if (HEADER_COLON_PAIR.matcher(Texts.trimToEmpty(line)).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void parseColonPairs(String[] lines, Map<String, String> result) {
        for (String line : lines) {
            String trimmed = Texts.trimToEmpty(line);
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            putHeader(result, trimmed.substring(0, idx), trimmed.substring(idx + 1));
        }
    }

    private static void parseAlternatingPairs(String[] lines, Map<String, String> result) {
        for (int i = 0; i < lines.length - 1; i += 2) {
            putHeader(result, lines[i], lines[i + 1]);
        }
    }

    private static void parseEqualsPairs(String[] lines, Map<String, String> result) {
        for (String line : lines) {
            String trimmed = Texts.trimToEmpty(line);
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            putHeader(result, trimmed.substring(0, idx), trimmed.substring(idx + 1));
        }
    }

    private static void putHeader(Map<String, String> result, String rawKey, String rawValue) {
        String key = Texts.trimToEmpty(rawKey);
        if (key.isEmpty()) {
            return;
        }
        result.put(key, Texts.trimToEmpty(rawValue));
    }

    private static boolean hasCommonSignatureHeader(Map<String, String> headers) {
        return headers.keySet().stream()
            .filter(k -> k != null)
            .map(k -> k.toLowerCase(Locale.ROOT))
            .anyMatch(k -> HEADER_X_ARGUS.equals(k) || HEADER_X_GORGON.equals(k));
    }

    private static String truncateForLog(String value) {
        if (value.length() <= RAW_LOG_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, RAW_LOG_MAX_LENGTH) + "...";
    }

    // 将 header map 转换为 unidbg signer 需要的 key\r\nvalue\r\n... 格式。
    private static String buildSignatureInputHeaders(Map<String, String> headerMap) {
        // 每个 header 约 key(20)+CRLF(2)+value(50)=72，条目间再加 CRLF(2)。
        int estimatedCapacity = headerMap.size() * 74;
        StringBuilder builder = new StringBuilder(estimatedCapacity);
        boolean first = true;
        for (Map.Entry<String, String> entry : headerMap.entrySet()) {
            if (!first) {
                builder.append("\r\n");
            }
            builder.append(entry.getKey()).append("\r\n").append(entry.getValue());
            first = false;
        }
        return builder.toString();
    }

    /**
     * 清理资源
     */
    public void destroy() {
        // 清理IdleFQ资源
        IdleFQ old;
        lock.lock();
        try {
            old = this.idleFQ;
            this.idleFQ = null;
        } finally {
            lock.unlock();
        }
        if (old != null) {
            old.destroy();
        }

        // 清理临时文件
        TempFileUtils.cleanup();

        log.info("签名服务资源释放完成");
    }

    private static void destroySignerQuietly(IdleFQ signer) {
        if (signer == null) {
            return;
        }
        try {
            signer.destroy();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
