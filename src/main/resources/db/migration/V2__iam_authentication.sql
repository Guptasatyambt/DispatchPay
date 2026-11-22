-- M01.S01/S02: OTP login, rate limiting, sessions with rotating refresh tokens.

-- Fixed-window counters. bucket_key holds a hash of the limited identifier, never the raw value.
CREATE TABLE rate_limit_bucket (
    bucket_key   VARCHAR(200) NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    hits         INTEGER      NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);

CREATE INDEX ix_rate_limit_bucket_expires_at ON rate_limit_bucket (expires_at);

-- Global identity, independent of any business tenant. Mobile is E.164.
CREATE TABLE user_account (
    id         UUID        PRIMARY KEY,
    mobile     VARCHAR(16) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_user_account_mobile UNIQUE (mobile),
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- OTP challenges exist for any well-formed mobile, registered or not, so requests reveal nothing.
-- Only an HMAC of the code is stored.
CREATE TABLE otp_challenge (
    id           UUID         PRIMARY KEY,
    mobile       VARCHAR(16)  NOT NULL,
    code_hash    CHAR(64)     NOT NULL,
    device_id    VARCHAR(128),
    status       VARCHAR(16)  NOT NULL,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    max_attempts INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    closed_at    TIMESTAMPTZ,
    CONSTRAINT ck_otp_challenge_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'SUPERSEDED', 'LOCKED'))
);

CREATE UNIQUE INDEX ux_otp_challenge_active_mobile ON otp_challenge (mobile) WHERE status = 'ACTIVE';
CREATE INDEX ix_otp_challenge_created_at ON otp_challenge (created_at);

-- One row per signed-in device. Revoking a session invalidates all of its refresh tokens.
CREATE TABLE auth_session (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES user_account (id),
    device_id         VARCHAR(128),
    device_name       VARCHAR(100),
    created_at        TIMESTAMPTZ  NOT NULL,
    last_refreshed_at TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    revoke_reason     VARCHAR(32)
);

CREATE INDEX ix_auth_session_active_user ON auth_session (user_id) WHERE revoked_at IS NULL;

-- Rotating refresh tokens (SHA-256 of a 256-bit random value). A used token presented again
-- is treated as theft and revokes the whole session.
CREATE TABLE refresh_token (
    id         UUID        PRIMARY KEY,
    session_id UUID        NOT NULL REFERENCES auth_session (id),
    token_hash CHAR(64)    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_token_session ON refresh_token (session_id);
