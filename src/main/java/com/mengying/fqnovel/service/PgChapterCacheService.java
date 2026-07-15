package com.mengying.fqnovel.service;

import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.config.FQCachePostgresConfig;
import com.mengying.fqnovel.utils.Texts;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PostgreSQL 章节缓存：持久化 bookId/chapterId 对应的章节响应。
 */
@Service
@Conditional(FQCachePostgresConfig.DbUrlPresentCondition.class)
public class PgChapterCacheService {

    private static final Logger log = LoggerFactory.getLogger(PgChapterCacheService.class);

    private static final String SELECT_SQL = """
        SELECT payload FROM chapter WHERE book_id = ? AND chapter_id = ? LIMIT 1
        """;

    private static final String UPSERT_SQL = """
        INSERT INTO chapter (book_id, chapter_id, payload, updated_at)
        VALUES (?, ?, ?, now())
        ON CONFLICT (book_id, chapter_id)
        DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()
        """;

    private static final String DELETE_SQL = """
        DELETE FROM chapter WHERE book_id = ? AND chapter_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgChapterCacheService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public FQNovelChapterInfo getChapter(String bookId, String chapterId) {
        if (!Texts.hasText(bookId) || !Texts.hasText(chapterId)) {
            return null;
        }

        String normalizedBookId = Texts.trimToEmpty(bookId);
        String normalizedChapterId = Texts.trimToEmpty(chapterId);

        try {
            String payload = jdbcTemplate.query(
                SELECT_SQL,
                ps -> {
                    ps.setString(1, normalizedBookId);
                    ps.setString(2, normalizedChapterId);
                },
                rs -> rs.next() ? rs.getString(1) : null
            );

            if (!Texts.hasText(payload)) {
                return null;
            }

            try {
                FQNovelChapterInfo chapterInfo = objectMapper.readValue(payload, FQNovelChapterInfo.class);
                if (!FQNovelChapterInfo.normalizeAndValidateForCache(normalizedBookId, normalizedChapterId, chapterInfo)) {
                    deleteQuietly(normalizedBookId, normalizedChapterId);
                    return null;
                }
                return chapterInfo;
            } catch (Exception parseEx) {
                deleteQuietly(normalizedBookId, normalizedChapterId);
                log.warn("PostgreSQL 章节缓存解析失败，已清理坏数据 - bookId: {}, chapterId: {}",
                    normalizedBookId, normalizedChapterId, parseEx);
                return null;
            }
        } catch (Exception e) {
            log.warn("读取 PostgreSQL 章节缓存失败 - bookId: {}, chapterId: {}", normalizedBookId, normalizedChapterId, e);
            return null;
        }
    }

    /**
     * 仅在章节内容有效时写入缓存。
     */
    public void saveChapterIfValid(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (!Texts.hasText(bookId) || !Texts.hasText(chapterId) || chapterInfo == null) {
            return;
        }

        String normalizedBookId = Texts.trimToEmpty(bookId);
        String normalizedChapterId = Texts.trimToEmpty(chapterId);

        if (!FQNovelChapterInfo.normalizeAndValidateForCache(normalizedBookId, normalizedChapterId, chapterInfo)) {
            if (log.isDebugEnabled()) {
                log.debug("跳过写入 PostgreSQL 缓存（章节数据无效）- bookId: {}, chapterId: {}", normalizedBookId, normalizedChapterId);
            }
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(chapterInfo);
            jdbcTemplate.update(UPSERT_SQL, normalizedBookId, normalizedChapterId, payload);
        } catch (Exception e) {
            log.warn("写入 PostgreSQL 章节缓存失败 - bookId: {}, chapterId: {}", normalizedBookId, normalizedChapterId, e);
        }
    }

    private void deleteQuietly(String bookId, String chapterId) {
        try {
            jdbcTemplate.update(DELETE_SQL, bookId, chapterId);
        } catch (Exception ignored) {
            // ignore
        }
    }

}
