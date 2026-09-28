-- A job can replace several people in the same video, each with its own source face.
CREATE TABLE job_swaps (
    id                BIGSERIAL PRIMARY KEY,
    job_id            UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    -- NULL means every face in the video gets this source face.
    target_face_index INT,
    face_id           UUID NOT NULL REFERENCES faces (id)
);
CREATE INDEX idx_job_swaps_job ON job_swaps (job_id);

-- Existing jobs had exactly one source face and at most one target.
INSERT INTO job_swaps (job_id, target_face_index, face_id)
SELECT id, target_face_index, face_id FROM jobs;
