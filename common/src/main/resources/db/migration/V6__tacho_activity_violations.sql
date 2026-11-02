-- RUTERA schema, step 6: tachograph import steps B and D (docs/TACHO_IMPORT_API.md): activity intervals from the
-- files and the violations of Regulation (EC) 561/2006 with the severity of Regulation (EU) 2016/403.
-- Deviations from the sketch (docs/DB_SCHEMA.md group 4):
--   tacho_activity keeps the intervals of every file as they are (source CARD or VU, card number of the card in the
--   slot, plate and VIN of the vehicle); the timeline of a driver is merged when it is read (a card day wins over
--   vehicle unit data, the newest file wins), so deleting a file needs no recalculation of the facts. The rows are
--   written by the import only and carry no created_at / updated_at / version;
--   driver_hos_day is not stored: the daily figures are computed on request from the merged timeline;
--   tacho_vehicle_use and tacho_place come later; tacho_file.facts_version marks files whose facts are extracted
--   (0 = not yet: files uploaded before this step are processed at the next start).

ALTER TABLE tacho_file ADD COLUMN facts_version INT NOT NULL DEFAULT 0;

-- Activity interval [start_at, end_at) of one slot. activity: DRIVING, WORK, AVAILABILITY, REST, UNKNOWN (card
-- withdrawn without a manual entry); slot 1 = driver, 2 = co-driver; crew = multi-manning.
CREATE TABLE tacho_activity (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    file_id             BIGINT        NOT NULL,
    source              VARCHAR(8)    NOT NULL,
    card_number         VARCHAR(32)   NULL,
    slot                TINYINT       NOT NULL,
    crew                BOOLEAN       NOT NULL DEFAULT FALSE,
    activity            VARCHAR(16)   NOT NULL,
    start_at            DATETIME(6)   NOT NULL,
    end_at              DATETIME(6)   NOT NULL,
    vehicle_plate       VARCHAR(20)   NULL,
    vehicle_vin         VARCHAR(17)   NULL,
    PRIMARY KEY (id),
    KEY ix_tacho_activity_firma_file (firma_id, file_id),
    KEY ix_tacho_activity_firma_card_start (firma_id, card_number, start_at),
    CONSTRAINT fk_tacho_activity_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_tacho_activity_file_id FOREIGN KEY (file_id) REFERENCES tacho_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Violation found by the 561/2006 rules; recomputed after every import touching the driver. The same violation
-- (driver, rule, start_at) keeps its acknowledgement. Values in minutes.
CREATE TABLE driver_violation (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    rule                VARCHAR(32)   NOT NULL,
    severity            VARCHAR(16)   NOT NULL,
    start_at            DATETIME(6)   NOT NULL,
    end_at              DATETIME(6)   NOT NULL,
    actual_value        INT           NOT NULL,
    limit_value         INT           NOT NULL,
    vehicle_plate       VARCHAR(20)   NULL,
    details             VARCHAR(500)  NULL,
    ack_by              BIGINT        NULL,
    ack_at              DATETIME(6)   NULL,
    ack_note            VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_driver_violation_driver_rule_start (driver_id, rule, start_at),
    KEY ix_driver_violation_firma_start (firma_id, start_at),
    CONSTRAINT fk_driver_violation_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_violation_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id),
    CONSTRAINT fk_driver_violation_ack_by FOREIGN KEY (ack_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
