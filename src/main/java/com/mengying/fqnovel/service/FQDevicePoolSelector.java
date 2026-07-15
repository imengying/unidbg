package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.utils.Texts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 设备池选择器。
 * 负责设备池裁剪、启动基线选择和运行时轮换候选选择。
 */
@Service
public class FQDevicePoolSelector {

    private final FQApiProperties fqApiProperties;
    private final AtomicInteger poolIndex = new AtomicInteger(0);
    private volatile String currentProfileName = "";

    public FQDevicePoolSelector(FQApiProperties fqApiProperties) {
        this.fqApiProperties = fqApiProperties;
    }

    public List<FQApiProperties.DeviceProfile> effectivePool() {
        List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(1, fqApiProperties.getDevicePoolSize());
        if (pool.size() > limit) {
            pool = new ArrayList<>(pool.subList(0, limit));
            fqApiProperties.setDevicePool(pool);
        }
        return pool;
    }

    public int resolveStartupIndex(List<FQApiProperties.DeviceProfile> pool) {
        if (pool == null || pool.isEmpty()) {
            return -1;
        }

        String startupName = Texts.trimToNull(fqApiProperties.getDevicePoolStartupName());
        int selectedIndex = findByName(pool, startupName);
        if (selectedIndex < 0 && fqApiProperties.isDevicePoolShuffleOnStartup() && pool.size() > 1) {
            Collections.shuffle(pool, ThreadLocalRandom.current());
        }
        return selectedIndex < 0 ? 0 : selectedIndex;
    }

    public RotationCandidate nextRotationCandidate(FQApiProperties.RuntimeProfile currentRuntime) {
        List<FQApiProperties.DeviceProfile> pool = effectivePool();
        if (pool.isEmpty()) {
            return null;
        }

        FQApiProperties.Device currentDevice = currentRuntime == null ? null : currentRuntime.getDeviceUnsafe();
        String currentDeviceId = deviceIdOf(currentDevice);
        String currentInstallId = installIdOf(currentDevice);

        for (int i = 0; i < pool.size(); i++) {
            int candidateIdx = Math.floorMod(poolIndex.getAndIncrement(), pool.size());
            FQApiProperties.DeviceProfile candidate = pool.get(candidateIdx);
            if (candidate == null) {
                continue;
            }

            String candidateDeviceId = profileDeviceId(candidate);
            String candidateInstallId = profileInstallId(candidate);
            if (isSamePhysicalDevice(candidateDeviceId, candidateInstallId, currentDeviceId, currentInstallId)) {
                continue;
            }
            return new RotationCandidate(candidate, candidateIdx);
        }
        return null;
    }

    public void markActiveProfile(FQApiProperties.DeviceProfile profile) {
        currentProfileName = Texts.nullToEmpty(profileName(profile));
    }

    public void setNextRotationIndex(int selectedIndex, int poolSize) {
        if (poolSize > 0) {
            poolIndex.set(Math.floorMod(selectedIndex + 1, poolSize));
        }
    }

    public String getCurrentProfileName() {
        return currentProfileName;
    }

    public record RotationCandidate(FQApiProperties.DeviceProfile profile, int index) {
    }

    private int findByName(List<FQApiProperties.DeviceProfile> pool, String name) {
        if (pool == null || pool.isEmpty() || !Texts.hasText(name)) {
            return -1;
        }
        for (int i = 0; i < pool.size(); i++) {
            FQApiProperties.DeviceProfile profile = pool.get(i);
            String normalizedProfileName = Texts.trimToNull(profileName(profile));
            if (name.equals(normalizedProfileName)) {
                return i;
            }
        }
        return -1;
    }

    private static String profileName(FQApiProperties.DeviceProfile profile) {
        return profile == null ? null : profile.getName();
    }

    private static String deviceField(FQApiProperties.Device device, Function<FQApiProperties.Device, String> getter) {
        return device == null ? null : getter.apply(device);
    }

    private static String profileDeviceId(FQApiProperties.DeviceProfile profile) {
        return deviceField(profile == null ? null : profile.getDevice(), FQApiProperties.Device::getDeviceId);
    }

    private static String profileInstallId(FQApiProperties.DeviceProfile profile) {
        return deviceField(profile == null ? null : profile.getDevice(), FQApiProperties.Device::getInstallId);
    }

    private static String deviceIdOf(FQApiProperties.Device device) {
        return deviceField(device, FQApiProperties.Device::getDeviceId);
    }

    private static String installIdOf(FQApiProperties.Device device) {
        return deviceField(device, FQApiProperties.Device::getInstallId);
    }

    private static boolean isSamePhysicalDevice(
        String candidateDeviceId,
        String candidateInstallId,
        String currentDeviceId,
        String currentInstallId
    ) {
        boolean sameDeviceId = candidateDeviceId != null && candidateDeviceId.equals(currentDeviceId);
        boolean sameInstallId = candidateInstallId != null && candidateInstallId.equals(currentInstallId);
        return sameDeviceId && sameInstallId;
    }
}
