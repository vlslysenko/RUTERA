-- RUTERA schema, step 15: Web Push subscriptions (driver cabinet notifications) and idempotency keys of replayed
-- requests (offline outbox of the driver cabinet).
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0); rows are deleted physically.

-- A browser or phone subscribed to Web Push for one user (RFC 8030/8291). endpoint is the push service URL of the
-- device; endpoint_hash (SHA-256 hex) makes it unique, as the URL is too long for an index. p256dh and auth are
-- the device keys (base64url) that encrypt the payload. failures counts failed deliveries in a row.
CREATE TABLE push_subscription (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    endpoint            VARCHAR(1000) NOT NULL,
    endpoint_hash       CHAR(64)      NOT NULL,
    p256dh              VARCHAR(128)  NOT NULL,
    auth                VARCHAR(64)   NOT NULL,
    user_agent          VARCHAR(255)  NULL,
    last_success_at     DATETIME(6)   NULL,
    failures            INT           NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_push_subscription_endpoint (endpoint_hash),
    KEY ix_push_subscription_firma_user (firma_id, user_id),
    CONSTRAINT fk_push_subscription_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_push_subscription_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The result of a request sent with an Idempotency-Key: a replay of the same key by the same user within 24 hours
-- gets this status and body instead of running again. status 0 = the first request is still running. Not tied to a
-- firma filter: the key belongs to the user.
CREATE TABLE idempotency_record (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    user_id             BIGINT        NOT NULL,
    idem_key            VARCHAR(64)   NOT NULL,
    method              VARCHAR(8)    NOT NULL,
    path                VARCHAR(255)  NOT NULL,
    status              INT           NOT NULL,
    content_type        VARCHAR(100)  NULL,
    body                MEDIUMTEXT    NULL,
    created_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_idempotency_record_user_key (user_id, idem_key),
    KEY ix_idempotency_record_created (created_at),
    CONSTRAINT fk_idempotency_record_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
