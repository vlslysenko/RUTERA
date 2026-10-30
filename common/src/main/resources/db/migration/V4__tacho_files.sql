-- RUTERA schema, step 4: tachograph import, step A (docs/TACHO_IMPORT_API.md): the uploaded DDD files, their
-- summary and the driver or vehicle they belong to. Facts from the files (tacho_activity, tacho_vehicle_use,
-- tacho_place, tacho_event) come in later steps.
-- Deviations from the sketch (docs/DB_SCHEMA.md group 4):
--   parse_status / parse_error are replaced by warnings_count + warnings: rejected files are not stored, so a
--   stored file is always parsed; warnings name the parts that could not be read;
--   match_kind records how the driver or vehicle was found; card and vehicle keys of the file are kept as
--   columns (card_issuing_country, card_valid_to, plate, plate_country) for the journal and its filters.

-- Uploaded DDD file. kind: DRIVER_CARD, VEHICLE_UNIT; generation: GEN1, GEN2, GEN2V2; source: UPLOAD (later API,
-- EMAIL, FOLDER); match_kind: CARD_NUMBER, DRIVER_IDENTIFICATION, VIN, PLATE, MANUAL, NONE. The bytes are in
-- stored_file (file_id). sha256 is unique per firma: uploading the same file again changes nothing.
CREATE TABLE tacho_file (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id              BIGINT        NOT NULL,
    kind                  VARCHAR(32)   NOT NULL,
    generation            VARCHAR(32)   NOT NULL,
    file_id               BIGINT        NOT NULL,
    sha256                CHAR(64)      NOT NULL,
    source                VARCHAR(32)   NOT NULL DEFAULT 'UPLOAD',
    card_number           VARCHAR(32)   NULL,
    card_issuing_country  CHAR(2)       NULL,
    card_valid_to         DATE          NULL,
    vin                   VARCHAR(17)   NULL,
    plate                 VARCHAR(20)   NULL,
    plate_country         CHAR(2)       NULL,
    period_from           DATE          NULL,
    period_to             DATE          NULL,
    downloaded_at         DATETIME(6)   NULL,
    warnings_count        INT           NOT NULL DEFAULT 0,
    warnings              JSON          NULL,
    driver_id             BIGINT        NULL,
    vehicle_id            BIGINT        NULL,
    match_kind            VARCHAR(32)   NOT NULL DEFAULT 'NONE',
    uploaded_by           BIGINT        NULL,
    uploaded_at           DATETIME(6)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    created_by            BIGINT        NULL,
    updated_at            DATETIME(6)   NULL,
    updated_by            BIGINT        NULL,
    version               INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_tacho_file_firma_sha256 (firma_id, sha256),
    KEY ix_tacho_file_firma_uploaded (firma_id, uploaded_at),
    KEY ix_tacho_file_firma_driver (firma_id, driver_id),
    KEY ix_tacho_file_firma_vehicle (firma_id, vehicle_id),
    KEY ix_tacho_file_firma_card (firma_id, card_number),
    KEY ix_tacho_file_firma_vin (firma_id, vin),
    CONSTRAINT fk_tacho_file_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_tacho_file_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_tacho_file_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id),
    CONSTRAINT fk_tacho_file_vehicle_id FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_tacho_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
