-- RUTERA schema, step 14: OAuth 2.1 for MCP clients such as the claude.ai connector (paid AI access module).
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0).

-- Clients registered through dynamic client registration (RFC 7591). Not tied to a firma: the firma comes with the
-- user who authorizes the client. redirect_uris: JSON array of exact URIs. client_secret_hash only for clients that
-- asked for client_secret_post or client_secret_basic (SHA-256 hex), NULL for public clients (PKCE only).
CREATE TABLE oauth_client (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    client_id           VARCHAR(64)   NOT NULL,
    client_name         VARCHAR(100)  NOT NULL,
    redirect_uris       JSON          NOT NULL,
    auth_method         VARCHAR(32)   NOT NULL,
    client_secret_hash  VARCHAR(128)  NULL,
    last_used_at        DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_oauth_client_client_id (client_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Authorization codes: 60 seconds, single use, bound to the client, the redirect URI and the PKCE challenge (S256).
-- api_token_id: the connection created from the code, revoked when the code is presented a second time.
CREATE TABLE oauth_code (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    client_id           BIGINT        NOT NULL,
    code_hash           VARCHAR(128)  NOT NULL,
    redirect_uri        VARCHAR(500)  NOT NULL,
    code_challenge      VARCHAR(128)  NOT NULL,
    scope               VARCHAR(100)  NOT NULL,
    resource            VARCHAR(255)  NULL,
    expires_at          DATETIME(6)   NOT NULL,
    used_at             DATETIME(6)   NULL,
    api_token_id        BIGINT        NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_oauth_code_hash (code_hash),
    KEY ix_oauth_code_firma_expires (firma_id, expires_at),
    CONSTRAINT fk_oauth_code_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_oauth_code_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_oauth_code_client_id FOREIGN KEY (client_id) REFERENCES oauth_client (id),
    CONSTRAINT fk_oauth_code_api_token_id FOREIGN KEY (api_token_id) REFERENCES api_token (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- A connection of an OAuth client is an api_token row of kind OAUTH, so the AI access gate, the rate limit, the
-- call log and the token list work for it as for personal tokens. token_hash = the current access token (one hour,
-- access_expires_at), refresh_hash = the current refresh token; both rotate on refresh. expires_at = end of the
-- refresh token (30 days after the last refresh).
ALTER TABLE api_token
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PERSONAL' AFTER user_id,
    ADD COLUMN oauth_client_id BIGINT NULL AFTER kind,
    ADD COLUMN access_expires_at DATETIME(6) NULL AFTER token_hash,
    ADD COLUMN refresh_hash VARCHAR(128) NULL AFTER access_expires_at,
    ADD UNIQUE KEY ux_api_token_refresh_hash (refresh_hash),
    ADD KEY ix_api_token_user_client (user_id, oauth_client_id),
    ADD CONSTRAINT fk_api_token_oauth_client_id FOREIGN KEY (oauth_client_id) REFERENCES oauth_client (id);
