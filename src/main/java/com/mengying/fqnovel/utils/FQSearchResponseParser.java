package com.mengying.fqnovel.utils;

import com.mengying.fqnovel.dto.FQSearchResponse;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 搜索响应解析器：从上游多种结构中提取统一的搜索结果。
 */
public final class FQSearchResponseParser {

    private FQSearchResponseParser() {
    }

    public static FQSearchResponse parseSearchResponse(JsonNode jsonResponse, int tabType) {
        FQSearchResponse searchResponse = new FQSearchResponse();

        JsonNode dataNode = dataNodeOf(jsonResponse);
        JsonNode searchTabs = searchTabsOf(jsonResponse, dataNode);

        boolean matchedTab = false;
        if (searchTabs != null && searchTabs.isArray()) {
            for (JsonNode tab : searchTabs) {
                if (tab.has("tab_type") && tab.get("tab_type").asInt() == tabType) {
                    matchedTab = true;
                    List<FQSearchResponse.BookItem> books = parseBooksFromTab(tab);
                    searchResponse.setBooks(books);

                    searchResponse.setTotal(tab.path("total").asInt(books.size()));
                    searchResponse.setHasMore(tab.path("has_more").asBoolean(false));
                    String tabSearchId = Texts.firstNonBlank(
                        searchIdOf(tab),
                        searchIdOf(dataNode),
                        searchIdOf(jsonResponse)
                    );
                    searchResponse.setSearchId(tabSearchId);
                    break;
                }
            }
        }

        if (!matchedTab && !hasBooks(searchResponse) && searchTabs != null && searchTabs.isArray()) {
            for (JsonNode tab : searchTabs) {
                List<FQSearchResponse.BookItem> books = parseBooksFromTab(tab);

                if (!books.isEmpty()) {
                    searchResponse.setBooks(books);
                    if (searchResponse.getTotal() == null) {
                        searchResponse.setTotal(tab.path("total").asInt(books.size()));
                    }
                    if (searchResponse.getHasMore() == null) {
                        Boolean hm = boolFromNode(tab.path("has_more"));
                        searchResponse.setHasMore(Boolean.TRUE.equals(hm));
                    }
                    if (Texts.isBlank(searchResponse.getSearchId())) {
                        String tabSearchId = searchIdOf(tab);
                        searchResponse.setSearchId(tabSearchId);
                    }
                    break;
                }
            }
        }

        if (!hasBooks(searchResponse)) {
            JsonNode booksNode = booksNodeOf(dataNode, jsonResponse);

            if (booksNode != null && booksNode.isArray()) {
                List<FQSearchResponse.BookItem> books = new ArrayList<>();
                addBooksFromArray(booksNode, books);
                searchResponse.setBooks(books);

                if (searchResponse.getTotal() == null) {
                    int total = dataNode != null ? dataNode.path("total").asInt(books.size()) : books.size();
                    searchResponse.setTotal(total);
                }
                if (searchResponse.getHasMore() == null) {
                    Boolean hasMore = null;
                    if (dataNode != null) {
                        hasMore = boolFromNode(dataNode.path("has_more"));
                        if (hasMore == null) {
                            hasMore = boolFromNode(dataNode.path("hasMore"));
                        }
                    }
                    searchResponse.setHasMore(Boolean.TRUE.equals(hasMore));
                }
            }
        }

        if (Texts.isBlank(searchResponse.getSearchId())) {
            String fallback = Texts.firstNonBlank(
                searchIdOf(dataNode),
                searchIdOf(jsonResponse)
            );
            searchResponse.setSearchId(fallback);
        }
        return searchResponse;
    }

    private static String textOf(JsonNode node, String fieldName) {
        if (node == null) {
            return "";
        }
        return node.path(fieldName).asString("");
    }

    private static JsonNode dataNodeOf(JsonNode root) {
        return root == null ? null : root.path("data");
    }

    private static JsonNode fieldOf(JsonNode node, String fieldName) {
        return node == null ? null : node.get(fieldName);
    }

    private static JsonNode firstArray(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode searchTabsOf(JsonNode root, JsonNode dataNode) {
        return firstArray(
            fieldOf(root, "search_tabs"),
            fieldOf(dataNode, "search_tabs"),
            fieldOf(root, "searchTabs"),
            fieldOf(dataNode, "searchTabs")
        );
    }

    private static JsonNode booksNodeOf(JsonNode dataNode, JsonNode root) {
        return firstArray(
            dataNode == null ? null : dataNode.path("books"),
            root == null ? null : root.path("books")
        );
    }

    private static boolean hasBooks(FQSearchResponse searchResponse) {
        if (searchResponse == null) {
            return false;
        }
        List<FQSearchResponse.BookItem> books = searchResponse.getBooks();
        return books != null && !books.isEmpty();
    }

    private static void addBooksFromArray(JsonNode booksNode, List<FQSearchResponse.BookItem> books) {
        if (booksNode == null || !booksNode.isArray()) {
            return;
        }
        for (JsonNode bookNode : booksNode) {
            books.add(parseBookItem(bookNode));
        }
    }

    private static List<FQSearchResponse.BookItem> parseBooksFromTab(JsonNode tab) {
        List<FQSearchResponse.BookItem> books = new ArrayList<>();
        JsonNode tabData = fieldOf(tab, "data");
        if (tabData != null && tabData.isArray()) {
            for (JsonNode cell : tabData) {
                JsonNode bookData = fieldOf(cell, "book_data");
                if (bookData == null || !bookData.isArray()) {
                    continue;
                }
                addBooksFromArray(bookData, books);
            }
        }

        JsonNode directBooks = fieldOf(tab, "books");
        if (books.isEmpty()) {
            addBooksFromArray(directBooks, books);
        }
        return books;
    }

    private static String searchIdOf(JsonNode node) {
        return Texts.firstNonBlank(
            textOf(node, "search_id"),
            textOf(node, "searchId"),
            textOf(node, "search_id_str")
        );
    }

    private static FQSearchResponse.BookItem parseBookItem(JsonNode bookNode) {
        FQSearchResponse.BookItem book = new FQSearchResponse.BookItem();
        if (bookNode == null || bookNode.isMissingNode() || bookNode.isNull()) {
            return book;
        }

        book.setBookId(bookNode.path("book_id").asString(""));
        book.setBookName(bookNode.path("book_name").asString(""));
        book.setAuthor(bookNode.path("author").asString(""));
        book.setDescription(Texts.firstNonBlank(
            bookNode.path("abstract").asString(""),
            bookNode.path("book_abstract_v2").asString("")
        ));
        book.setCoverUrl(Texts.firstNonBlank(
            bookNode.path("thumb_url").asString(""),
            bookNode.path("detail_page_thumb_url").asString("")
        ));
        book.setLastChapterTitle(bookNode.path("last_chapter_title").asString(""));
        book.setCategory(bookNode.path("category").asString(""));
        book.setWordCount(bookNode.path("word_number").asLong(0L));

        return book;
    }

    public static String deepFindSearchId(JsonNode root) {
        if (root == null) {
            return "";
        }

        String direct = searchIdOf(root);
        if (Texts.hasText(direct)) {
            return direct;
        }

        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node == null) {
                continue;
            }

            if (node.isObject()) {
                String found = searchIdOf(node);
                if (Texts.hasText(found)) {
                    return found;
                }
                for (JsonNode child : node) {
                    if (child != null) {
                        stack.push(child);
                    }
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    stack.push(child);
                }
            }
        }

        return "";
    }

    private static Boolean boolFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        String s = Texts.trimToNull(node.asString(""));
        if (s == null || "null".equalsIgnoreCase(s)) {
            return null;
        }
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return false;
        }
        return null;
    }
}
