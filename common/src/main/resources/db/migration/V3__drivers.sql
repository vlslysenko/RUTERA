-- RUTERA schema, step 3: drivers (group 3 of docs/DB_SCHEMA.md), first part: driver, driver_card and
-- driver_document. Absences, working days, pay rates and payments come in later steps.
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0). Soft delete on driver (a dictionary); cards and documents
-- are deleted physically.
-- Deviations from the sketch:
--   driver.default_vehicle_id is not created: the pairing lives in vehicle.default_driver_id only (one place),
--   the driver card shows the vehicles that point at the driver;
--   driver_card.card_number is unique per firma (UNIQUE(firma_id, card_number)), as all unique keys.
-- vehicle.default_driver_id (V2) gets its foreign key here.

-- Driver as an employee. user_id is set when the driver has a login (driver cabinet); one driver per user.
-- status: ACTIVE, INACTIVE. country: ISO 3166-1 alpha-2.
CREATE TABLE driver (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    user_id             BIGINT        NULL,
    first_name          VARCHAR(100)  NOT NULL,
    last_name           VARCHAR(100)  NOT NULL,
    birth_date          DATE          NULL,
    phone               VARCHAR(40)   NULL,
    email               VARCHAR(254)  NULL,
    country             CHAR(2)       NULL,
    employment_from     DATE          NULL,
    employment_to       DATE          NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    notes               VARCHAR(2000) NULL,
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_driver_firma_user (firma_id, user_id),
    KEY ix_driver_firma_name (firma_id, last_name, first_name),
    CONSTRAINT fk_driver_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Tachograph driver card (history: a card is replaced every 5 years). The DDD import finds the driver by
-- card_number and sets last_download_at (28-day download reminder). generation: GEN1, GEN2.
CREATE TABLE driver_card (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    card_number         VARCHAR(32)   NOT NULL,
    issuing_country     CHAR(2)       NOT NULL,
    valid_from          DATE          NULL,
    valid_to            DATE          NULL,
    generation          VARCHAR(32)   NULL,
    last_download_at    DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_driver_card_firma_number (firma_id, card_number),
    KEY ix_driver_card_firma_driver (firma_id, driver_id),
    CONSTRAINT fk_driver_card_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_card_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Document with a deadline. kind: LICENSE, CPC_CODE95, MEDICAL, PSYCHO, ADR, PASSPORT, VISA, WORK_PERMIT, OTHER.
CREATE TABLE driver_document (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    number              VARCHAR(64)   NULL,
    valid_from          DATE          NULL,
    valid_to            DATE          NULL,
    file_id             BIGINT        NULL,
    remind_days         INT           NOT NULL DEFAULT 30,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_driver_document_firma_driver (firma_id, driver_id),
    KEY ix_driver_document_firma_valid_to (firma_id, valid_to),
    CONSTRAINT fk_driver_document_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_document_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id),
    CONSTRAINT fk_driver_document_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE vehicle
    ADD KEY ix_vehicle_firma_default_driver (firma_id, default_driver_id),
    ADD CONSTRAINT fk_vehicle_default_driver_id FOREIGN KEY (default_driver_id) REFERENCES driver (id);
