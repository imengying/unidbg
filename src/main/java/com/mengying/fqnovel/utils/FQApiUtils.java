package com.mengying.fqnovel.utils;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.config.FQApiRuntimeProfileManager;
import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQSearchUpstreamRequest;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * FQ API 通用工具类
 * 用于构建 API 请求参数和请求头。
 */
@Component
public class FQApiUtils {

    private static final String HEADER_AUTHORIZATION = "authorization";
    private static final String HEADER_X_READING_REQUEST = "x-reading-request";
    private static final String AUTHORIZATION_BEARER = "Bearer";

    private static final String VALUE_ZERO = "0";
    private static final String VALUE_ONE = "1";
    private static final String OS_ANDROID = "android";
    private static final String NETWORK_WIFI = "wifi";
    private static final String CHANNEL_GOOGLE_PLAY = "googleplay";
    private static final String APP_NOVEL = "novelapp";
    private static final String SSMIX_A = "a";
    private static final String LANGUAGE_ZH = "zh";
    private static final String DRAGON_DEVICE_PHONE = "phone";
    private static final String DEFAULT_OS_API = "32";
    private static final String DEFAULT_OS_VERSION = "13";
    private static final String KEY_REGISTER_TS = "0";

    private static final Set<String> ENCODE_WHITELIST = Set.of(
        "query", "client_ab_info", "search_source_id", "search_id", "device_type", "resolution", "rom_version"
    );

    private final FQApiProperties fqApiProperties;
    private final FQApiRuntimeProfileManager runtimeProfileManager;

    public FQApiUtils(FQApiProperties fqApiProperties, FQApiRuntimeProfileManager runtimeProfileManager) {
        this.fqApiProperties = fqApiProperties;
        this.runtimeProfileManager = runtimeProfileManager;
    }

    /**
     * 构建通用 API 请求参数。
     */
    public Map<String, String> buildCommonApiParams() {
        FQApiProperties.Device device = requireRuntimeDevice();
        String installId = requireDeviceValue(device.getInstallId(), "fq.api.device.install-id");
        String deviceId = requireDeviceValue(device.getDeviceId(), "fq.api.device.device-id");
        String aid = requireDeviceValue(device.getAid(), "fq.api.device.aid");
        String updateVersionCode = requireDeviceValue(device.getUpdateVersionCode(), "fq.api.device.update-version-code");
        String versionCode = Texts.trimToEmpty(device.getVersionCode());

        Map<String, String> params = new HashMap<>(32);
        params.put("iid", installId);
        params.put("device_id", deviceId);
        params.put("ac", NETWORK_WIFI);
        params.put("channel", CHANNEL_GOOGLE_PLAY);
        params.put("aid", aid);
        params.put("app_name", APP_NOVEL);
        params.put("version_code", versionCode);
        params.put("version_name", Texts.trimToEmpty(device.getVersionName()));
        params.put("device_platform", OS_ANDROID);
        params.put("os", OS_ANDROID);
        params.put("ssmix", SSMIX_A);
        params.put("device_type", Texts.trimToEmpty(device.getDeviceType()));
        params.put("device_brand", Texts.trimToEmpty(device.getDeviceBrand()));
        params.put("language", LANGUAGE_ZH);
        params.put("os_api", Texts.defaultIfBlank(device.getOsApi(), DEFAULT_OS_API));
        params.put("os_version", Texts.defaultIfBlank(device.getOsVersion(), DEFAULT_OS_VERSION));
        params.put("manifest_version_code", versionCode);
        params.put("resolution", Texts.trimToEmpty(device.getResolution()));
        params.put("dpi", Texts.trimToEmpty(device.getDpi()));
        params.put("update_version_code", updateVersionCode);
        params.put("_rticket", String.valueOf(System.currentTimeMillis()));
        params.put("host_abi", Texts.trimToEmpty(device.getHostAbi()));
        params.put("dragon_device_type", DRAGON_DEVICE_PHONE);
        params.put("pv_player", versionCode);
        params.put("compliance_status", VALUE_ZERO);
        params.put("need_personal_recommend", VALUE_ONE);
        params.put("player_so_load", VALUE_ONE);
        params.put("is_android_pad_screen", VALUE_ZERO);
        params.put("rom_version", Texts.trimToEmpty(device.getRomVersion()));
        params.put("cdid", Texts.trimToEmpty(device.getCdid()));
        return params;
    }

    /**
     * 构建 batch_full 特定参数。
     */
    public Map<String, String> buildBatchFullParams(String itemIds, String bookId, boolean download) {
        Map<String, String> params = buildCommonApiParams();
        params.put("item_ids", itemIds);
        params.put("key_register_ts", KEY_REGISTER_TS);
        params.put("book_id", bookId);
        params.put("req_type", download ? "0" : "1");
        return params;
    }

    public Map<String, String> buildCommonHeaders() {
        return buildCommonHeaders(System.currentTimeMillis());
    }

    /**
     * 构建通用请求头（指定时间戳）。
     */
    public Map<String, String> buildCommonHeaders(long currentTime) {
        Map<String, String> headers = new LinkedHashMap<>(16);
        FQApiProperties.RuntimeProfile runtimeProfile = runtimeProfileManager.getRuntimeProfile();
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
        String installId = safeGet(device, FQApiProperties.Device::getInstallId);

        headers.put("accept", "application/json; charset=utf-8,application/x-protobuf");
        String cookie = CookieUtils.normalizeInstallId(safeGet(runtimeProfile, FQApiProperties.RuntimeProfile::getCookie), installId);
        putIfNotNull(headers, "cookie", cookie);
        String userAgent = safeGet(runtimeProfile, FQApiProperties.RuntimeProfile::getUserAgent);
        putIfNotNull(headers, "user-agent", userAgent);
        headers.put("accept-encoding", "gzip");
        headers.put("x-xs-from-web", "0");
        headers.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
        headers.put("x-reading-request", currentTime + "-" + ThreadLocalRandom.current().nextInt(2_000_000_000));
        headers.put("sdk-version", "2");
        headers.put("x-tt-store-region-src", "did");
        headers.put("x-tt-store-region", "cn-zj");
        headers.put("lc", "101");
        headers.put("x-ss-req-ticket", String.valueOf(currentTime));
        headers.put("passport-sdk-version", "50564");
        String aid = safeGet(device, FQApiProperties.Device::getAid);
        putIfNotNull(headers, "x-ss-dp", aid);
        return headers;
    }

    /**
     * 构建搜索接口请求头。
     */
    public Map<String, String> buildSearchHeaders() {
        Map<String, String> base = buildCommonHeaders();
        if (base.containsKey(HEADER_AUTHORIZATION)) {
            return base;
        }

        Map<String, String> ordered = new LinkedHashMap<>(base.size() + 1);
        for (Map.Entry<String, String> entry : base.entrySet()) {
            ordered.put(entry.getKey(), entry.getValue());
            if (HEADER_X_READING_REQUEST.equalsIgnoreCase(entry.getKey())) {
                ordered.put(HEADER_AUTHORIZATION, AUTHORIZATION_BEARER);
            }
        }
        if (!ordered.containsKey(HEADER_AUTHORIZATION)) {
            ordered.put(HEADER_AUTHORIZATION, AUTHORIZATION_BEARER);
        }
        return ordered;
    }

    /**
     * 构建 RegisterKey 请求头（指定时间戳）。
     */
    public Map<String, String> buildRegisterKeyHeaders(long currentTime) {
        Map<String, String> headers = buildCommonHeaders(currentTime);
        headers.put("content-type", "application/json");
        return headers;
    }

    /**
     * 构建带参数 URL。
     */
    public String buildUrlWithParams(String baseUrl, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return baseUrl;
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("?");

        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                urlBuilder.append("&");
            }
            String key = entry.getKey();
            String value = entry.getValue();
            urlBuilder.append(key).append("=");

            if (ENCODE_WHITELIST.contains(key)) {
                urlBuilder.append(encodeIfNeeded(value));
            } else {
                urlBuilder.append(Texts.nullToEmpty(value));
            }
            first = false;
        }

        return urlBuilder.toString();
    }

    /**
     * 对原始值或已百分号编码的值进行规范化编码。
     * '+' 始终按字面量处理，避免 C++ 之类的查询被上游解析为空格。
     */
    private String encodeIfNeeded(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value;
        if (containsPercentEncodedByte(value)) {
            try {
                normalized = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                normalized = value;
            }
        }
        return URLEncoder.encode(normalized, StandardCharsets.UTF_8);
    }

    private static boolean containsPercentEncodedByte(String value) {
        for (int i = 0; i + 2 < value.length(); i++) {
            if (value.charAt(i) == '%'
                && Character.digit(value.charAt(i + 1), 16) >= 0
                && Character.digit(value.charAt(i + 2), 16) >= 0) {
                return true;
            }
        }
        return false;
    }

    public Map<String, String> buildSearchParams(FQSearchUpstreamRequest searchRequest) {
        Map<String, String> params = buildCommonApiParams();

        params.put("bookshelf_search_plan", String.valueOf(searchRequest.getBookshelfSearchPlan()));
        params.put("offset", String.valueOf(searchRequest.getOffset()));
        params.put("from_rs", bool01(searchRequest.getFromRs()));
        params.put("user_is_login", String.valueOf(searchRequest.getUserIsLogin()));
        params.put("bookstore_tab", String.valueOf(searchRequest.getBookstoreTab()));
        params.put("query", searchRequest.getQuery());
        params.put("count", String.valueOf(searchRequest.getCount()));
        params.put("search_source", String.valueOf(searchRequest.getSearchSource()));
        params.put("clicked_content", searchRequest.getClickedContent());
        params.put("search_source_id", searchRequest.getSearchSourceId());
        params.put("use_lynx", bool01(searchRequest.getUseLynx()));
        params.put("use_correct", bool01(searchRequest.getUseCorrect()));
        params.put("last_search_page_interval", String.valueOf(searchRequest.getLastSearchPageInterval()));
        params.put("line_words_num", String.valueOf(searchRequest.getLineWordsNum()));
        params.put("tab_name", searchRequest.getTabName());
        params.put("last_consume_interval", String.valueOf(searchRequest.getLastConsumeInterval()));
        params.put("pad_column_cover", String.valueOf(searchRequest.getPadColumnCover()));
        params.put("is_first_enter_search", bool01(searchRequest.getIsFirstEnterSearch()));

        putTrimmedIfHasText(params, "search_id", searchRequest.getSearchId());

        Integer passback = Objects.requireNonNullElse(searchRequest.getPassback(), searchRequest.getOffset());
        params.put("passback", String.valueOf(passback));

        putIfNotNull(params, "tab_type", searchRequest.getTabType());

        if (Boolean.TRUE.equals(searchRequest.getIsFirstEnterSearch())) {
            params.put("client_ab_info", searchRequest.getClientAbInfo());
        }

        params.put("normal_session_id", searchRequest.getNormalSessionId());
        params.put("cold_start_session_id", searchRequest.getColdStartSessionId());
        params.put("charging", String.valueOf(searchRequest.getCharging()));
        params.put("screen_brightness", String.valueOf(searchRequest.getScreenBrightness()));
        params.put("battery_pct", String.valueOf(searchRequest.getBatteryPct()));
        params.put("down_speed", String.valueOf(searchRequest.getDownSpeed()));
        params.put("sys_dark_mode", String.valueOf(searchRequest.getSysDarkMode()));
        params.put("app_dark_mode", String.valueOf(searchRequest.getAppDarkMode()));
        params.put("font_scale", String.valueOf(searchRequest.getFontScale()));
        params.put("is_android_pad_screen", String.valueOf(searchRequest.getIsAndroidPadScreen()));
        params.put("network_type", String.valueOf(searchRequest.getNetworkType()));
        params.put("current_volume", String.valueOf(searchRequest.getCurrentVolume()));
        return params;
    }

    private static String bool01(Boolean value) {
        return Boolean.TRUE.equals(value) ? "1" : "0";
    }

    public Map<String, String> buildDirectoryParams(FQDirectoryRequest directoryRequest) {
        Map<String, String> params = buildCommonApiParams();

        if (directoryRequest == null) {
            params.put("book_type", "0");
            params.put("book_id", "");
            params.put("need_version", String.valueOf(Boolean.TRUE));
            return params;
        }

        Integer bookType = directoryRequest.getBookType();
        Boolean needVersion = directoryRequest.getNeedVersion();
        boolean minimalResponse = Boolean.TRUE.equals(directoryRequest.getMinimalResponse());
        String bookId = directoryRequest.getBookId();
        boolean finalNeedVersion = !minimalResponse
            && Objects.requireNonNullElse(needVersion, Boolean.TRUE);

        params.put("book_type", String.valueOf(intOrDefault(bookType, 0)));
        params.put("book_id", Texts.nullToEmpty(bookId));
        params.put("need_version", String.valueOf(finalNeedVersion));

        putTrimmedIfHasText(params, "item_data_list_md5", directoryRequest.getItemDataListMd5());
        putTrimmedIfHasText(params, "catalog_data_md5", directoryRequest.getCatalogDataMd5());
        putTrimmedIfHasText(params, "book_info_md5", directoryRequest.getBookInfoMd5());
        return params;
    }

    public String getBaseUrl() {
        return fqApiProperties.getBaseUrl();
    }

    /**
     * 搜索/目录相关接口要求使用 c 域名。
     */
    public String getSearchApiBaseUrl() {
        return normalizeSearchApiBaseUrl(fqApiProperties.getBaseUrl());
    }

    public String getServerDeviceId() {
        return requireDeviceValue(requireRuntimeDevice().getDeviceId(), "fq.api.device.device-id");
    }

    private static String normalizeSearchApiBaseUrl(String baseUrl) {
        if (!Texts.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.replace("api5-normal-sinfonlineb", "api5-normal-sinfonlinec");
    }

    private static int intOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static void putTrimmedIfHasText(Map<String, String> params, String key, String value) {
        String trimmed = Texts.trimToNull(value);
        if (trimmed != null) {
            params.put(key, trimmed);
        }
    }

    private static void putIfNotNull(Map<String, String> params, String key, Object value) {
        if (value != null) {
            params.put(key, String.valueOf(value));
        }
    }

    private FQApiProperties.Device requireRuntimeDevice() {
        FQApiProperties.RuntimeProfile runtimeProfile = runtimeProfileManager == null ? null : runtimeProfileManager.getRuntimeProfile();
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
        if (device == null) {
            throw new IllegalStateException("缺少设备配置：fq.api.device");
        }
        return device;
    }

    private static String requireDeviceValue(String value, String fieldName) {
        if (!Texts.hasText(value)) {
            throw new IllegalStateException("缺少设备配置字段: " + fieldName);
        }
        return Texts.trimToEmpty(value);
    }

    private static <T, R> R safeGet(T source, Function<T, R> getter) {
        return source == null ? null : getter.apply(source);
    }
}
