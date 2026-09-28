-- Videos are analyzed after upload to find the distinct people in them, so the user can pick
-- which person to swap. Rows uploaded before this migration were never analyzed.
ALTER TABLE videos
    ADD COLUMN analysis_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN analysis_error  TEXT;
UPDATE videos SET analysis_status = 'FAILED', analysis_error = 'Analiz bu özellikten önce yüklenen videolar için yapılmadı';

CREATE TABLE video_faces (
    id            UUID PRIMARY KEY,
    video_id      UUID         NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    face_index    INT          NOT NULL,
    thumbnail_key VARCHAR(512) NOT NULL,
    occurrences   INT          NOT NULL,
    UNIQUE (video_id, face_index)
);

-- NULL means every face in the video is swapped.
ALTER TABLE jobs ADD COLUMN target_face_index INT;
