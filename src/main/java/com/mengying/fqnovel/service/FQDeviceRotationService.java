package com.mengying.fqnovel.service;

import tools.jackson.databind.JsonNode;
import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.config.FQApiRuntimeProfileManager;
import com.mengying.fqnovel.dto.FQSearchRequest;
import com.mengying.fqnovel.dto.FQSearchResponse;
import com.mengying.fqnovel.dto.FQSearchUpstreamRequest;
import com.mengying.fqnovel.utils.FQApiUtils;
import com.mengying.fqnovel.utils.FQSearchResponseParser;
import com.mengying.fqnovel.utils.Texts;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备信息风控（ILLEGAL_ACCESS）时的自愈：自动更换设备信息并刷新 registerkey。
 * 不写入配置文件、不重启进程，仅在内存中生效。
 */
@Service
public class FQDeviceRotationService {

    private static final Logger log = LoggerFactory.getLogger(FQDeviceRotationService.class);

    private final FQApiProperties fqApiProperties;
    private final FQApiRuntimeProfileManager runtimeProfileManager;
    private final FQRegisterKeyService registerKeyService;
    private final FQApiUtils fqApiUtils;
    private final FQSearchRequestEnricher searchRequestEnricher;
    private final UpstreamSignedRequestService upstreamSignedRequestService;
    private final FQDevicePoolSelector devicePoolSelector;
    private final FQDeviceProfileApplier deviceProfileApplier;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile long lastRotateAtMs = 0L;

    public FQDeviceRotationService(
        FQApiProperties fqApiProperties,
        FQApiRuntimeProfileManager runtimeProfileManager,
        FQRegisterKeyService registerKeyService,
        FQApiUtils fqApiUtils,
        FQSearchRequestEnricher searchRequestEnricher,
        UpstreamSignedRequestService upstreamSignedRequestService,
        FQDevicePoolSelector devicePoolSelector,
        FQDeviceProfileApplier deviceProfileApplier
    ) {
        this.fqApiProperties = fqApiProperties;
        this.runtimeProfileManager = runtimeProfileManager;
        this.registerKeyService = registerKeyService;
        this.fqApiUtils = fqApiUtils;
        this.searchRequestEnricher = searchRequestEnricher;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
        this.devicePoolSelector = devicePoolSelector;
        this.deviceProfileApplier = deviceProfileApplier;
    }

    public String getCurrentProfileName() {
        return devicePoolSelector.getCurrentProfileName();
    }

    @PostConstruct
    public void initDevicePoolOnStartup() {
        List<FQApiProperties.DeviceProfile> pool = devicePoolSelector.effectivePool();
        if (pool.isEmpty()) {
            return;
        }

        int selectedIndex = devicePoolSelector.resolveStartupIndex(pool);
        if (selectedIndex < 0) {
            return;
        }

        if (fqApiProperties.isDevicePoolProbeOnStartup() && pool.size() > 1) {
            selectedIndex = selectStartupProfileWithProbe(pool, selectedIndex);
        } else {
            applySelectedProfile(pool.get(selectedIndex));
        }

        devicePoolSelector.setNextRotationIndex(selectedIndex, pool.size());
        FQApiProperties.DeviceProfile selected = pool.get(selectedIndex);
        log.info("选择设备：{} (ID={})", profileName(selected), profileDeviceId(selected));
    }

    /**
     * 尝试旋转设备（带冷却时间，避免并发风暴）。
     *
     * @return true 表示完成设备切换，false 表示未切换
     */
    public boolean rotateIfNeeded(String reason) {
        return rotateInternal(reason, false);
    }

    /**
     * 强制旋转设备（忽略冷却时间），用于单次请求内的自愈流程。
     * 仍然会加锁，避免并发下“乱序切换”。
     */
    public boolean forceRotate(String reason) {
        return rotateInternal(reason, true);
    }

    private int selectStartupProfileWithProbe(List<FQApiProperties.DeviceProfile> pool, int selectedIndex) {
        int maxAttempts = Math.max(1, fqApiProperties.getDevicePoolProbeMaxAttempts());
        maxAttempts = Math.min(maxAttempts, pool.size());

        int attempt = 0;
        int idx = selectedIndex;
        boolean ok = false;
        while (attempt < maxAttempts) {
            FQApiProperties.DeviceProfile candidate = pool.get(idx);
            boolean applied = applySelectedProfile(candidate);
            attempt++;
            ok = applied && probeSearchOk();
            if (ok) {
                selectedIndex = idx;
                break;
            }
            idx = (idx + 1) % pool.size();
        }

        if (!ok) {
            FQApiProperties.DeviceProfile fallback = pool.get(selectedIndex);
            applySelectedProfile(fallback);
            log.warn("启动设备探测失败，回退设备：设备名={}", profileName(fallback));
        } else {
            log.info("设备探测通过：序号={}, 尝试={}", selectedIndex, attempt);
        }
        return selectedIndex;
    }

    private boolean applySelectedProfile(FQApiProperties.DeviceProfile profile) {
        boolean applied = deviceProfileApplier.apply(profile);
        if (applied) {
            devicePoolSelector.markActiveProfile(profile);
        }
        return applied;
    }

    /**
     * 启动轻量探测：发起一次 search 请求，要求返回 code=0 且包含 search_id 或 books 列表。
     * 不做解密/不依赖 registerkey，仅用于剔除“启动就被拦截”的设备指纹。
     */
    private boolean probeSearchOk() {
        try {
            FQSearchRequest searchRequest = new FQSearchRequest();
            searchRequest.setQuery("系统");
            searchRequest.setOffset(0);
            searchRequest.setCount(1);
            searchRequest.setTabType(1);
            searchRequest.setPassback(0);
            FQSearchUpstreamRequest upstreamRequest = searchRequestEnricher.enrich(searchRequest);
            upstreamRequest.setIsFirstEnterSearch(true);

            String url = fqApiUtils.getSearchApiBaseUrl() + "/reading/bookapi/search/tab/v";
            Map<String, String> params = fqApiUtils.buildSearchParams(upstreamRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            Map<String, String> headers = fqApiUtils.buildSearchHeaders();
            UpstreamSignedRequestService.UpstreamJsonResult upstream = upstreamSignedRequestService.executeSignedJsonGet(fullUrl, headers);
            if (upstream == null) {
                return false;
            }

            String body = upstream.responseSnippet();
            String trimmedBody = Texts.trimToNull(body);
            if (trimmedBody == null || trimmedBody.startsWith("<")) {
                return false;
            }

            JsonNode root = upstream.jsonBody();
            if (root.path("code").asInt(-1) != 0) {
                return false;
            }
            String searchId = FQSearchResponseParser.deepFindSearchId(root);
            if (Texts.hasText(searchId)) {
                return true;
            }

            FQSearchResponse parsed = FQSearchResponseParser.parseSearchResponse(root, 1);
            return parsed != null && parsed.getBooks() != null && !parsed.getBooks().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean rotateInternal(String reason, boolean ignoreCooldown) {
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, fqApiProperties.getDeviceRotateCooldownMs());
        if (isInRotateCooldown(ignoreCooldown, now, cooldownMs)) {
            return false;
        }

        lock.lock();
        try {
            now = System.currentTimeMillis();
            cooldownMs = Math.max(0L, fqApiProperties.getDeviceRotateCooldownMs());
            if (isInRotateCooldown(ignoreCooldown, now, cooldownMs)) {
                return false;
            }

            if (!rotateFromConfiguredPool(reason)) {
                log.warn("检测到异常，但设备池中无可切换设备：原因={}", reason);
                return false;
            }

            lastRotateAtMs = now;
            refreshRegisterKeyAfterRotation();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean rotateFromConfiguredPool(String reason) {
        FQDevicePoolSelector.RotationCandidate candidate =
            devicePoolSelector.nextRotationCandidate(runtimeProfileManager.getRuntimeProfile());
        if (candidate == null) {
            return false;
        }
        if (!applySelectedProfile(candidate.profile())) {
            return false;
        }

        log.warn(
            "检测到异常，已切换设备：序号={}, 设备名={}, 设备ID={}, 安装ID={}, 原因={}",
            candidate.index(),
            profileName(candidate.profile()),
            profileDeviceId(candidate.profile()),
            profileInstallId(candidate.profile()),
            reason
        );
        return true;
    }

    private boolean isInRotateCooldown(boolean ignoreCooldown, long nowMs, long cooldownMs) {
        return !ignoreCooldown && nowMs - lastRotateAtMs < cooldownMs;
    }

    private void refreshRegisterKeyAfterRotation() {
        try {
            registerKeyService.invalidateCurrentKey();
            registerKeyService.refreshRegisterKey();
        } catch (Exception e) {
            log.warn("设备旋转后刷新 registerkey 失败（可忽略，下次请求会再刷新）", e);
        }
    }

    private static String profileName(FQApiProperties.DeviceProfile profile) {
        return profile == null ? null : profile.getName();
    }

    private static String profileDeviceId(FQApiProperties.DeviceProfile profile) {
        FQApiProperties.Device device = profile == null ? null : profile.getDevice();
        return device == null ? null : device.getDeviceId();
    }

    private static String profileInstallId(FQApiProperties.DeviceProfile profile) {
        FQApiProperties.Device device = profile == null ? null : profile.getDevice();
        return device == null ? null : device.getInstallId();
    }
}
