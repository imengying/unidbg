package com.mengying.fqnovel.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存构建工具：统一 LRU + TTL 策略。
 */
public final class LocalCacheFactory {

    private LocalCacheFactory() {
    }

    public static <K, V> Cache<K, V> build(long maxEntries, long ttlMs) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
            .maximumSize(Math.max(1L, maxEntries));
        if (ttlMs > 0) {
            builder = builder.expireAfterWrite(ttlMs, TimeUnit.MILLISECONDS);
        }
        return builder.build();
    }

    public static <K, V> Cache<K, V> buildWeighted(
        long maxWeight,
        long ttlMs,
        Weigher<? super K, ? super V> weigher
    ) {
        if (weigher == null) {
            throw new IllegalArgumentException("weigher must not be null");
        }

        Caffeine<K, V> builder = Caffeine.newBuilder()
            .maximumWeight(Math.max(1L, maxWeight))
            .weigher(weigher);
        if (ttlMs > 0) {
            builder = builder.expireAfterWrite(ttlMs, TimeUnit.MILLISECONDS);
        }
        return builder.build();
    }
}
