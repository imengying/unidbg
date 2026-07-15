CREATE TABLE IF NOT EXISTS chapter (
    book_id VARCHAR(64) NOT NULL,
    chapter_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (book_id, chapter_id)
);

CREATE INDEX IF NOT EXISTS chapter_idx ON chapter(updated_at);
