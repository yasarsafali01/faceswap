-- Uploaded source photos are analyzed like videos: every face in them is cropped so the user can pick
-- which one to use when a photo shows several people.
ALTER TABLE faces
    ADD COLUMN analysis_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN analysis_error  TEXT;
-- Photos uploaded before this migration were never analyzed; they still work, the worker then uses
-- the largest face in them.
UPDATE faces SET analysis_status = 'FAILED', analysis_error = 'Bu fotoğraf yüz analizi özelliğinden önce yüklendi';

CREATE TABLE face_detections (
    id        UUID PRIMARY KEY,
    face_id   UUID         NOT NULL REFERENCES faces (id) ON DELETE CASCADE,
    det_index INT          NOT NULL,
    crop_key  VARCHAR(512) NOT NULL,
    UNIQUE (face_id, det_index)
);

-- Which face of the source photo to use; NULL when the photo has a single face.
ALTER TABLE job_swaps ADD COLUMN source_face_index INT;
