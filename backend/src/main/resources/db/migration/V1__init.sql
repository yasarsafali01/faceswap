CREATE TABLE roles (
    id   SMALLSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL UNIQUE
);

INSERT INTO roles (name) VALUES ('USER'), ('ADMIN');

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id BIGINT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE videos (
    id            UUID PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    object_key    VARCHAR(512) NOT NULL,
    original_name VARCHAR(255),
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_videos_user ON videos (user_id, created_at DESC);

CREATE TABLE faces (
    id            UUID PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    object_key    VARCHAR(512) NOT NULL,
    original_name VARCHAR(255),
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_faces_user ON faces (user_id, created_at DESC);

CREATE TABLE jobs (
    id            UUID PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    video_id      UUID        NOT NULL REFERENCES videos (id),
    face_id       UUID        NOT NULL REFERENCES faces (id),
    status        VARCHAR(20) NOT NULL,
    progress      SMALLINT    NOT NULL DEFAULT 0,
    enhance       BOOLEAN     NOT NULL DEFAULT TRUE,
    result_key    VARCHAR(512),
    thumbnail_key VARCHAR(512),
    error_message TEXT,
    consent_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ
);
CREATE INDEX idx_jobs_user ON jobs (user_id, created_at DESC);
CREATE INDEX idx_jobs_status ON jobs (status);

CREATE TABLE job_logs (
    id         BIGSERIAL PRIMARY KEY,
    job_id     UUID        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    level      VARCHAR(10) NOT NULL,
    message    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_logs_job ON job_logs (job_id, created_at);
