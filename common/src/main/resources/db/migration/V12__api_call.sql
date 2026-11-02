-- RUTERA schema, step 12: audit of public API calls (paid AI access module; api_token itself exists since V1).
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0). One row per /api call made with a token; old rows will be
-- removed by a retention job later.

CREATE TABLE api_call (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    token_id            BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    method              VARCHAR(8)    NOT NULL,
    path                VARCHAR(255)  NOT NULL,
    status              INT           NOT NULL,
    duration_ms         INT           NOT NULL,
    ip                  VARCHAR(45)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_api_call_firma_token (firma_id, token_id, id),
    KEY ix_api_call_firma_created (firma_id, created_at),
    CONSTRAINT fk_api_call_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_api_call_token_id FOREIGN KEY (token_id) REFERENCES api_token (id),
    CONSTRAINT fk_api_call_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
