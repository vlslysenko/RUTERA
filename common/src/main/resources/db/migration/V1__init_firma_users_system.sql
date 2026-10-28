-- RUTERA schema, step 1.
-- Group 1 (firma, users, roles, tokens) and group 8 (system tables) of docs/DB_SCHEMA.md.
-- Conventions: docs/DB_SCHEMA.md section 0, docs/REQUIREMENTS.md section 16.
--   id BIGINT AUTO_INCREMENT; firma_id on tenant tables; enums as VARCHAR; time in UTC DATETIME(6);
--   common columns created_at/created_by/updated_at/updated_by/version; soft delete only on app_user.
--   Index names ix_<table>_<cols>, unique ux_..., foreign keys fk_<table>_<col>.
-- Deviations from the sketch: setting tables use setting_key/setting_value (KEY is a reserved word);
--   subscription (stage 2) is not created yet.

-- ---------------------------------------------------------------------------
-- 1. Firma (tenant root). Standalone: exactly one row, id = 1.
-- ---------------------------------------------------------------------------
CREATE TABLE firma (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(200) NOT NULL,
    short_code      VARCHAR(32)  NOT NULL,
    country         CHAR(2)      NOT NULL,
    vat_id          VARCHAR(32)  NULL,
    address         VARCHAR(500) NULL,
    email           VARCHAR(254) NULL,
    lang            VARCHAR(5)   NOT NULL DEFAULT 'en',
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    base_currency   CHAR(3)      NOT NULL DEFAULT 'EUR',
    plan            VARCHAR(32)  NOT NULL DEFAULT 'STANDARD',
    vehicle_limit   INT          NOT NULL DEFAULT 10,
    ai_access       BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    is_test         BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at      DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_firma_short_code (short_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Role with an individual privilege set, per firma (SMDP OwnOpRole).
CREATE TABLE custom_role (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    name            VARCHAR(100) NOT NULL,
    level           INT          NOT NULL DEFAULT 5,
    privileges      JSON         NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_custom_role_firma_name (firma_id, name),
    CONSTRAINT fk_custom_role_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- User. role: OWNER, DISPATCHER, LOGISTICIAN, ACCOUNTANT, DRIVER, API, CUSTOM, SUPERUSER.
-- firma_id is NULL only for the service superuser. Login is globally unique (login = e-mail).
-- The only business table without the automatic tenant filter: login looks the user up before the firma is known.
CREATE TABLE app_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NULL,
    login           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NULL,
    role            VARCHAR(32)  NOT NULL,
    custom_role_id  BIGINT       NULL,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    phone           VARCHAR(40)  NULL,
    lang            VARCHAR(5)   NOT NULL DEFAULT 'en',
    totp_secret_enc VARCHAR(255) NULL,
    totp_enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_logins   INT          NOT NULL DEFAULT 0,
    locked_until    DATETIME(6)  NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    settings        JSON         NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_app_user_login (login),
    KEY ix_app_user_firma_id (firma_id),
    CONSTRAINT fk_app_user_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_app_user_custom_role_id FOREIGN KEY (custom_role_id) REFERENCES custom_role (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Per-user override of a single privilege key (fleet.vehicle/update -> true/false).
CREATE TABLE user_privilege (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    privilege_key   VARCHAR(100) NOT NULL,
    allowed         BOOLEAN      NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_privilege_user_key (firma_id, user_id, privilege_key),
    CONSTRAINT fk_user_privilege_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_user_privilege_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Remember-me tokens with rotation (series + hashed token).
CREATE TABLE remember_me_token (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    series          VARCHAR(64)  NOT NULL,
    token_hash      VARCHAR(128) NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    last_used_at    DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_remember_me_token_series (series),
    KEY ix_remember_me_token_firma_user (firma_id, user_id),
    CONSTRAINT fk_remember_me_token_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_remember_me_token_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Invitation or password reset (kind = INVITE, RESET); only the SHA-256 hash is stored.
CREATE TABLE activation_token (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    token_hash      CHAR(64)     NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    used_at         DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_activation_token_hash (token_hash),
    KEY ix_activation_token_firma_user (firma_id, user_id),
    CONSTRAINT fk_activation_token_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_activation_token_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Backup codes for two-factor login.
CREATE TABLE totp_recovery_code (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    code_hash       VARCHAR(128) NOT NULL,
    used_at         DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_totp_recovery_code_firma_user (firma_id, user_id),
    CONSTRAINT fk_totp_recovery_code_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_totp_recovery_code_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Personal token for the public API and MCP (paid AI access module). Issued only when firma.ai_access = true.
CREATE TABLE api_token (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    name            VARCHAR(100) NOT NULL,
    token_prefix    VARCHAR(12)  NOT NULL,
    token_hash      VARCHAR(128) NOT NULL,
    scopes          JSON         NULL,
    expires_at      DATETIME(6)  NULL,
    last_used_at    DATETIME(6)  NULL,
    revoked_at      DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_api_token_hash (token_hash),
    KEY ix_api_token_firma_user (firma_id, user_id),
    CONSTRAINT fk_api_token_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_api_token_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 8. System: files, audit, mail queue, notifications, settings, job locks.
-- ---------------------------------------------------------------------------

-- Any stored file (CMR, DDD, fuel export, invoice PDF, scan, receipt). storage = DISK or S3.
CREATE TABLE stored_file (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    storage         VARCHAR(16)  NOT NULL,
    storage_key     VARCHAR(512) NOT NULL,
    original_name   VARCHAR(255) NOT NULL,
    mime            VARCHAR(127) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    sha256          CHAR(64)     NOT NULL,
    uploaded_by     BIGINT       NULL,
    uploaded_at     DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_stored_file_storage_key (storage_key),
    KEY ix_stored_file_firma_kind (firma_id, kind),
    CONSTRAINT fk_stored_file_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_stored_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Audit log. channel = UI, API, MCP, SYSTEM. request_id matches the MDC in log files.
-- Append-only; no foreign keys and PRIMARY KEY (id, at) so it can be range-partitioned by month later.
CREATE TABLE audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NULL,
    user_id         BIGINT       NULL,
    at              DATETIME(6)  NOT NULL,
    channel         VARCHAR(16)  NOT NULL,
    module          VARCHAR(32)  NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    entity          VARCHAR(64)  NULL,
    entity_id       BIGINT       NULL,
    summary         VARCHAR(500) NOT NULL,
    details         JSON         NULL,
    request_id      VARCHAR(36)  NOT NULL,
    ip              VARCHAR(45)  NULL,
    PRIMARY KEY (id, at),
    KEY ix_audit_log_firma_at (firma_id, at),
    KEY ix_audit_log_entity (firma_id, entity, entity_id),
    KEY ix_audit_log_user_at (user_id, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Outgoing mail queue instead of a message broker: written in the business transaction, sent by a scheduler.
CREATE TABLE email_outbox (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT        NOT NULL,
    to_emails       VARCHAR(1000) NOT NULL,
    cc_emails       VARCHAR(1000) NULL,
    subject         VARCHAR(255)  NOT NULL,
    template_key    VARCHAR(64)   NOT NULL,
    params          JSON          NOT NULL,
    attachments     JSON          NULL,
    status          VARCHAR(16)   NOT NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)   NULL,
    sent_at         DATETIME(6)   NULL,
    error           TEXT          NULL,
    created_at      DATETIME(6)   NOT NULL,
    created_by      BIGINT        NULL,
    updated_at      DATETIME(6)   NULL,
    updated_by      BIGINT        NULL,
    version         INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_email_outbox_status_next (status, next_attempt_at),
    KEY ix_email_outbox_firma_created (firma_id, created_at),
    CONSTRAINT fk_email_outbox_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- In-app notifications (bell in the top bar).
CREATE TABLE notification (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    body            TEXT         NULL,
    link            VARCHAR(500) NULL,
    read_at         DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_notification_firma_user_read (firma_id, user_id, read_at),
    CONSTRAINT fk_notification_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_notification_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Firma settings: key -> JSON value.
CREATE TABLE firma_setting (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    firma_id        BIGINT       NOT NULL,
    setting_key     VARCHAR(100) NOT NULL,
    setting_value   JSON         NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BIGINT       NULL,
    updated_at      DATETIME(6)  NULL,
    updated_by      BIGINT       NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_firma_setting_key (firma_id, setting_key),
    CONSTRAINT fk_firma_setting_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Global runtime settings (never secrets: passwords and keys live in environment variables).
CREATE TABLE app_setting (
    setting_key     VARCHAR(100) NOT NULL,
    setting_value   JSON         NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Scheduler locks (ShedLock JDBC layout) so that jobs run once across several instances.
CREATE TABLE job_lock (
    name            VARCHAR(64)  NOT NULL,
    lock_until      TIMESTAMP(3) NOT NULL,
    locked_at       TIMESTAMP(3) NOT NULL,
    locked_by       VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
