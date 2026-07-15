package com.mengying.fqnovel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下载/上游请求相关配置。
 */
@ConfigurationProperties(prefix = "fq.download")
public class FQDownloadProperties {

    /**
     * 章节接口（batch_full）最小请求间隔（ms），用于限制章节请求 QPS。
     */
    private long requestIntervalMs = 500;

    private Retry retry = new Retry();
    private Upstream upstream = new Upstream();
    private Prefetch prefetch = new Prefetch();
    private Cache cache = new Cache();
    private AutoRestart autoRestart = new AutoRestart();

    public long getRequestIntervalMs() {
        return requestIntervalMs;
    }

    public void setRequestIntervalMs(long requestIntervalMs) {
        this.requestIntervalMs = requestIntervalMs;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry == null ? new Retry() : retry;
    }

    public Upstream getUpstream() {
        return upstream;
    }

    public void setUpstream(Upstream upstream) {
        this.upstream = upstream == null ? new Upstream() : upstream;
    }

    public Prefetch getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(Prefetch prefetch) {
        this.prefetch = prefetch == null ? new Prefetch() : prefetch;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache == null ? new Cache() : cache;
    }

    public AutoRestart getAutoRestart() {
        return autoRestart;
    }

    public void setAutoRestart(AutoRestart autoRestart) {
        this.autoRestart = autoRestart == null ? new AutoRestart() : autoRestart;
    }

    public static class Retry {
        private int maxRetries = 3;
        private long delayMs = 1500;
        private long maxDelayMs = 10000;

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
        }
    }

    public static class Upstream {
        private long connectTimeoutMs = 8000;
        private long readTimeoutMs = 15000;

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Prefetch {
        private int chapterSize = 30;
        private int executorCoreSize = 2;
        private int executorMaxSize = 2;
        private int executorQueueCapacity = 256;
        private int executorKeepAliveSeconds = 60;

        public int getChapterSize() {
            return chapterSize;
        }

        public void setChapterSize(int chapterSize) {
            this.chapterSize = chapterSize;
        }

        public int getExecutorCoreSize() {
            return executorCoreSize;
        }

        public void setExecutorCoreSize(int executorCoreSize) {
            this.executorCoreSize = executorCoreSize;
        }

        public int getExecutorMaxSize() {
            return executorMaxSize;
        }

        public void setExecutorMaxSize(int executorMaxSize) {
            this.executorMaxSize = executorMaxSize;
        }

        public int getExecutorQueueCapacity() {
            return executorQueueCapacity;
        }

        public void setExecutorQueueCapacity(int executorQueueCapacity) {
            this.executorQueueCapacity = executorQueueCapacity;
        }

        public int getExecutorKeepAliveSeconds() {
            return executorKeepAliveSeconds;
        }

        public void setExecutorKeepAliveSeconds(int executorKeepAliveSeconds) {
            this.executorKeepAliveSeconds = executorKeepAliveSeconds;
        }
    }

    public static class Cache {
        private long chapterMaxWeightBytes = 64L * 1024 * 1024;
        private int chapterMaxEntries = 2000;
        private long chapterTtlMs = 30 * 60 * 1000L;
        private long chapterNegativeTtlMs = 10 * 60 * 1000L;
        private long chapterFailureLogCooldownMs = 3 * 60 * 1000L;
        private long chapterEmptyRetryBackoffMs = 30 * 1000L;
        private boolean chapterIncludeRawContent = false;
        private int searchMaxEntries = 256;
        private long searchTtlMs = 45 * 1000L;
        private int apiDirectoryMaxEntries = 512;
        private long apiDirectoryTtlMs = 10 * 60 * 1000L;

        public long getChapterMaxWeightBytes() {
            return chapterMaxWeightBytes;
        }

        public void setChapterMaxWeightBytes(long chapterMaxWeightBytes) {
            this.chapterMaxWeightBytes = chapterMaxWeightBytes;
        }

        public int getChapterMaxEntries() {
            return chapterMaxEntries;
        }

        public void setChapterMaxEntries(int chapterMaxEntries) {
            this.chapterMaxEntries = chapterMaxEntries;
        }

        public long getChapterTtlMs() {
            return chapterTtlMs;
        }

        public void setChapterTtlMs(long chapterTtlMs) {
            this.chapterTtlMs = chapterTtlMs;
        }

        public long getChapterNegativeTtlMs() {
            return chapterNegativeTtlMs;
        }

        public void setChapterNegativeTtlMs(long chapterNegativeTtlMs) {
            this.chapterNegativeTtlMs = chapterNegativeTtlMs;
        }

        public long getChapterFailureLogCooldownMs() {
            return chapterFailureLogCooldownMs;
        }

        public void setChapterFailureLogCooldownMs(long chapterFailureLogCooldownMs) {
            this.chapterFailureLogCooldownMs = chapterFailureLogCooldownMs;
        }

        public long getChapterEmptyRetryBackoffMs() {
            return chapterEmptyRetryBackoffMs;
        }

        public void setChapterEmptyRetryBackoffMs(long chapterEmptyRetryBackoffMs) {
            this.chapterEmptyRetryBackoffMs = chapterEmptyRetryBackoffMs;
        }

        public boolean isChapterIncludeRawContent() {
            return chapterIncludeRawContent;
        }

        public void setChapterIncludeRawContent(boolean chapterIncludeRawContent) {
            this.chapterIncludeRawContent = chapterIncludeRawContent;
        }

        public int getSearchMaxEntries() {
            return searchMaxEntries;
        }

        public void setSearchMaxEntries(int searchMaxEntries) {
            this.searchMaxEntries = searchMaxEntries;
        }

        public long getSearchTtlMs() {
            return searchTtlMs;
        }

        public void setSearchTtlMs(long searchTtlMs) {
            this.searchTtlMs = searchTtlMs;
        }

        public int getApiDirectoryMaxEntries() {
            return apiDirectoryMaxEntries;
        }

        public void setApiDirectoryMaxEntries(int apiDirectoryMaxEntries) {
            this.apiDirectoryMaxEntries = apiDirectoryMaxEntries;
        }

        public long getApiDirectoryTtlMs() {
            return apiDirectoryTtlMs;
        }

        public void setApiDirectoryTtlMs(long apiDirectoryTtlMs) {
            this.apiDirectoryTtlMs = apiDirectoryTtlMs;
        }
    }

    public static class AutoRestart {
        private boolean enabled = true;
        private int errorThreshold = 3;
        private int upstreamEmptyErrorThreshold = 8;
        private long windowMs = 5 * 60 * 1000L;
        private long minIntervalMs = 60 * 1000L;
        private long forceHaltAfterMs = 10_000L;
        private boolean selfHealEnabled = true;
        private long selfHealCooldownMs = 60 * 1000L;
        private long exitDelayMs = 5_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getErrorThreshold() {
            return errorThreshold;
        }

        public void setErrorThreshold(int errorThreshold) {
            this.errorThreshold = errorThreshold;
        }

        public int getUpstreamEmptyErrorThreshold() {
            return upstreamEmptyErrorThreshold;
        }

        public void setUpstreamEmptyErrorThreshold(int upstreamEmptyErrorThreshold) {
            this.upstreamEmptyErrorThreshold = upstreamEmptyErrorThreshold;
        }

        public long getWindowMs() {
            return windowMs;
        }

        public void setWindowMs(long windowMs) {
            this.windowMs = windowMs;
        }

        public long getMinIntervalMs() {
            return minIntervalMs;
        }

        public void setMinIntervalMs(long minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }

        public long getForceHaltAfterMs() {
            return forceHaltAfterMs;
        }

        public void setForceHaltAfterMs(long forceHaltAfterMs) {
            this.forceHaltAfterMs = forceHaltAfterMs;
        }

        public boolean isSelfHealEnabled() {
            return selfHealEnabled;
        }

        public void setSelfHealEnabled(boolean selfHealEnabled) {
            this.selfHealEnabled = selfHealEnabled;
        }

        public long getSelfHealCooldownMs() {
            return selfHealCooldownMs;
        }

        public void setSelfHealCooldownMs(long selfHealCooldownMs) {
            this.selfHealCooldownMs = selfHealCooldownMs;
        }

        public long getExitDelayMs() {
            return exitDelayMs;
        }

        public void setExitDelayMs(long exitDelayMs) {
            this.exitDelayMs = exitDelayMs;
        }
    }
}
