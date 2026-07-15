package com.mengying.fqnovel.dto;

import com.mengying.fqnovel.config.LenientIntegerDeserializer;
import com.mengying.fqnovel.config.LenientLongDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 目录接口中的书籍信息（仅保留当前映射需要字段）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FQNovelBookInfoResp {

    @JsonProperty("book_name")
    private String bookName;

    private String author;

    @JsonProperty("abstract")
    private String abstractContent;

    @JsonProperty("book_abstract_v2")
    private String bookAbstractV2;

    @JsonProperty("thumb_url")
    private String thumbUrl;

    @JsonProperty("word_number")
    @JsonDeserialize(using = LenientLongDeserializer.class)
    private Long wordNumber;

    @JsonProperty("last_chapter_title")
    private String lastChapterTitle;

    private String category;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    private Integer status;

    @JsonProperty("serial_count")
    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    private Integer serialCount;

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getAbstractContent() {
        return abstractContent;
    }

    public void setAbstractContent(String abstractContent) {
        this.abstractContent = abstractContent;
    }

    public String getBookAbstractV2() {
        return bookAbstractV2;
    }

    public void setBookAbstractV2(String bookAbstractV2) {
        this.bookAbstractV2 = bookAbstractV2;
    }

    public String getThumbUrl() {
        return thumbUrl;
    }

    public void setThumbUrl(String thumbUrl) {
        this.thumbUrl = thumbUrl;
    }

    public Long getWordNumber() {
        return wordNumber;
    }

    public void setWordNumber(Long wordNumber) {
        this.wordNumber = wordNumber;
    }

    public String getLastChapterTitle() {
        return lastChapterTitle;
    }

    public void setLastChapterTitle(String lastChapterTitle) {
        this.lastChapterTitle = lastChapterTitle;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSerialCount() {
        return serialCount;
    }

    public void setSerialCount(Integer serialCount) {
        this.serialCount = serialCount;
    }
}
