package com.mengying.fqnovel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mengying.fqnovel.config.LenientIntegerDeserializer;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * FQ书籍目录响应DTO - 基于实际API响应结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQDirectoryResponse {

    /**
     * 是否需要重新获取Ban状态
     */
    @JsonProperty("ban_recover")
    private Boolean banRecover;

    /**
     * 附加数据列表
     */
    @JsonProperty("additional_item_data_list")
    private JsonNode additionalItemDataList;

    /**
     * 目录数据 - 章节索引信息
     */
    @JsonProperty("catalog_data")
    private List<CatalogItem> catalogData;

    /**
     * 章节详细数据列表
     */
    @JsonProperty("item_data_list")
    private List<ItemData> itemDataList;

    /**
     * 字段缓存状态
     */
    @JsonProperty("field_cache_status")
    private FieldCacheStatus fieldCacheStatus;

    /**
     * 书籍信息
     */
    @JsonProperty("book_info")
    private FQNovelBookInfoResp bookInfo;

    /**
     * 连载数量
     */
    @JsonProperty("serial_count")
    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    private Integer serialCount;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogItem {

        /**
         * 目录ID
         */
        @JsonProperty("catalog_id")
        private String catalogId;

        /**
         * 目录标题
         */
        @JsonProperty("catalog_title")
        private String catalogTitle;

        /**
         * 章节ID
         */
        @JsonProperty("item_id")
        private String itemId;

        public String getCatalogId() {
            return catalogId;
        }

        public void setCatalogId(String catalogId) {
            this.catalogId = catalogId;
        }

        public String getCatalogTitle() {
            return catalogTitle;
        }

        public void setCatalogTitle(String catalogTitle) {
            this.catalogTitle = catalogTitle;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }
    }

    /**
     * 章节详细数据（精简版 - 仅保留 Legado 需要的字段）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemData {

        /**
         * 章节ID（Legado 必需）
         */
        @JsonProperty("item_id")
        private String itemId;

        /**
         * 章节标题（Legado 必需）
         */
        private String title;

        /**
         * 章节序号（从1开始，由服务端计算，用于内部逻辑）
         */
        @JsonProperty("chapter_index")
        private Integer chapterIndex;

        /**
         * 是否为最新章节（由服务端计算，用于内部逻辑）
         */
        @JsonProperty("is_latest")
        private Boolean isLatest;

        /**
         * 首次通过时间（时间戳，用于内部排序）
         */
        @JsonProperty("first_pass_time")
        private Integer firstPassTime;

        /**
         * 首次通过时间（格式化字符串，由服务端计算）
         */
        @JsonProperty("first_pass_time_str")
        private String firstPassTimeStr;

        /**
         * 排序序号（由服务端计算）
         */
        @JsonProperty("sort_order")
        private Integer sortOrder;

        /**
         * 是否免费（由服务端计算）
         */
        @JsonProperty("is_free")
        private Boolean isFree;

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Integer getChapterIndex() {
            return chapterIndex;
        }

        public void setChapterIndex(Integer chapterIndex) {
            this.chapterIndex = chapterIndex;
        }

        public Boolean getIsLatest() {
            return isLatest;
        }

        public void setIsLatest(Boolean latest) {
            isLatest = latest;
        }

        public Integer getFirstPassTime() {
            return firstPassTime;
        }

        public void setFirstPassTime(Integer firstPassTime) {
            this.firstPassTime = firstPassTime;
        }

        public String getFirstPassTimeStr() {
            return firstPassTimeStr;
        }

        public void setFirstPassTimeStr(String firstPassTimeStr) {
            this.firstPassTimeStr = firstPassTimeStr;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getIsFree() {
            return isFree;
        }

        public void setIsFree(Boolean free) {
            isFree = free;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldCacheStatus {

        /**
         * 书籍信息缓存状态
         */
        @JsonProperty("book_info")
        private CacheInfo bookInfo;

        /**
         * 章节数据列表缓存状态
         */
        @JsonProperty("item_data_list")
        private CacheInfo itemDataList;

        public CacheInfo getBookInfo() {
            return bookInfo;
        }

        public void setBookInfo(CacheInfo bookInfo) {
            this.bookInfo = bookInfo;
        }

        public CacheInfo getItemDataList() {
            return itemDataList;
        }

        public void setItemDataList(CacheInfo itemDataList) {
            this.itemDataList = itemDataList;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CacheInfo {

        /**
         * 是否命中缓存
         */
        private Boolean hit;

        /**
         * MD5校验值
         */
        private String md5;

        public Boolean getHit() {
            return hit;
        }

        public void setHit(Boolean hit) {
            this.hit = hit;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }
    }

    public Boolean getBanRecover() {
        return banRecover;
    }

    public void setBanRecover(Boolean banRecover) {
        this.banRecover = banRecover;
    }

    public JsonNode getAdditionalItemDataList() {
        return additionalItemDataList;
    }

    public void setAdditionalItemDataList(JsonNode additionalItemDataList) {
        this.additionalItemDataList = additionalItemDataList;
    }

    public List<CatalogItem> getCatalogData() {
        return catalogData;
    }

    public void setCatalogData(List<CatalogItem> catalogData) {
        this.catalogData = catalogData;
    }

    public List<ItemData> getItemDataList() {
        return itemDataList;
    }

    public void setItemDataList(List<ItemData> itemDataList) {
        this.itemDataList = itemDataList;
    }

    public FieldCacheStatus getFieldCacheStatus() {
        return fieldCacheStatus;
    }

    public void setFieldCacheStatus(FieldCacheStatus fieldCacheStatus) {
        this.fieldCacheStatus = fieldCacheStatus;
    }

    public FQNovelBookInfoResp getBookInfo() {
        return bookInfo;
    }

    public void setBookInfo(FQNovelBookInfoResp bookInfo) {
        this.bookInfo = bookInfo;
    }

    public Integer getSerialCount() {
        return serialCount;
    }

    public void setSerialCount(Integer serialCount) {
        this.serialCount = serialCount;
    }

}
