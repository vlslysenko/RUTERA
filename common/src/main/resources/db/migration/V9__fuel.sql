-- RUTERA schema, step 9: fuel cards, imports of provider exports and fuel transactions (group 7 of docs/DB_SCHEMA.md).
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0); all rows are deleted physically.
-- Deviations from the sketch:
--   fuel_card keeps card_hash (SHA-256 of provider and normalized number) for matching, the number itself only masked;
--   fuel_transaction is not partitioned yet (planned together with tacho_activity), so foreign keys stay;
--   fuel_transaction.match_kind records how vehicle and driver were found; plate_raw keeps the plate of the export;
--   fuel_mapping (new) keeps the column mapping a firma confirmed per provider.

-- Fuel card: provider DKV, UTA, SHELL, BP, ORLEN, E100, AS24, OMV, OTHER. Linked to a vehicle and/or a driver.
CREATE TABLE fuel_card (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    provider            VARCHAR(16)   NOT NULL,
    card_number_masked  VARCHAR(32)   NOT NULL,
    card_hash           CHAR(64)      NOT NULL,
    vehicle_id          BIGINT        NULL,
    driver_id           BIGINT        NULL,
    valid_to            DATE          NULL,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    notes               VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fuel_card_firma_provider_hash (firma_id, provider, card_hash),
    KEY ix_fuel_card_firma_vehicle (firma_id, vehicle_id),
    CONSTRAINT fk_fuel_card_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_fuel_card_vehicle_id FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_fuel_card_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One uploaded export: counts, the mapping used (JSON field -> header) and the first row errors (JSON).
CREATE TABLE fuel_import (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    provider            VARCHAR(16)   NOT NULL,
    file_id             BIGINT        NULL,
    file_name           VARCHAR(255)  NOT NULL,
    period_from         DATE          NULL,
    period_to           DATE          NULL,
    rows_total          INT           NOT NULL DEFAULT 0,
    rows_new            INT           NOT NULL DEFAULT 0,
    rows_dup            INT           NOT NULL DEFAULT 0,
    rows_error          INT           NOT NULL DEFAULT 0,
    status              VARCHAR(16)   NOT NULL,
    mapping             JSON          NULL,
    errors              JSON          NULL,
    imported_by         BIGINT        NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_fuel_import_firma_created (firma_id, created_at),
    CONSTRAINT fk_fuel_import_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_fuel_import_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_fuel_import_imported_by FOREIGN KEY (imported_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- A fuelling or another card operation. product: DIESEL, ADBLUE, PETROL, LNG, CNG, TOLL, SERVICE, OTHER; unit L, KG,
-- PCS; match_kind: CARD, PLATE, MANUAL, NONE; source: IMPORT, MANUAL. (provider, external_id) is unique in the firma,
-- so importing the same file again only counts duplicates.
CREATE TABLE fuel_transaction (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    at                  DATETIME(6)   NOT NULL,
    provider            VARCHAR(16)   NOT NULL,
    external_id         VARCHAR(80)   NOT NULL,
    card_id             BIGINT        NULL,
    vehicle_id          BIGINT        NULL,
    driver_id           BIGINT        NULL,
    trip_id             BIGINT        NULL,
    station             VARCHAR(200)  NULL,
    country             CHAR(2)       NULL,
    product             VARCHAR(16)   NOT NULL,
    product_raw         VARCHAR(100)  NULL,
    quantity            DECIMAL(12,3) NULL,
    unit                VARCHAR(8)    NULL,
    unit_price          DECIMAL(12,4) NULL,
    net                 DECIMAL(14,2) NULL,
    vat                 DECIMAL(14,2) NULL,
    gross               DECIMAL(14,2) NOT NULL,
    currency            CHAR(3)       NOT NULL,
    odometer_km         INT           NULL,
    plate_raw           VARCHAR(20)   NULL,
    match_kind          VARCHAR(8)    NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    import_id           BIGINT        NULL,
    notes               VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fuel_transaction_firma_provider_ext (firma_id, provider, external_id),
    KEY ix_fuel_transaction_firma_at (firma_id, at),
    KEY ix_fuel_transaction_firma_vehicle_at (firma_id, vehicle_id, at),
    KEY ix_fuel_transaction_firma_import (firma_id, import_id),
    CONSTRAINT fk_fuel_transaction_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_fuel_transaction_card_id FOREIGN KEY (card_id) REFERENCES fuel_card (id),
    CONSTRAINT fk_fuel_transaction_vehicle_id FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_fuel_transaction_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id),
    CONSTRAINT fk_fuel_transaction_trip_id FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_fuel_transaction_import_id FOREIGN KEY (import_id) REFERENCES fuel_import (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The column mapping a firma confirmed for a provider: columns JSON {field: header}, options JSON
-- {dateFormat, decimalComma, currency}.
CREATE TABLE fuel_mapping (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    provider            VARCHAR(16)   NOT NULL,
    columns_json        JSON          NOT NULL,
    options_json        JSON          NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fuel_mapping_firma_provider (firma_id, provider),
    CONSTRAINT fk_fuel_mapping_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
