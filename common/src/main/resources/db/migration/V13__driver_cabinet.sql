-- RUTERA schema, step 13: driver cabinet step 2 (absence requests, trip files from drivers, firma setting).
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0).

-- Absences requested by drivers: REQUESTED until the office decides (APPROVED or REJECTED, with a note).
-- Absences entered by the office are APPROVED at once, so the existing rows are APPROVED.
ALTER TABLE driver_absence
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN decision_note VARCHAR(500) NULL,
    ADD KEY ix_driver_absence_firma_status (firma_id, status);

-- Whether drivers see their own pay rates and payments in the cabinet.
ALTER TABLE firma
    ADD COLUMN drivers_see_pay BOOLEAN NOT NULL DEFAULT FALSE;

-- Photos and documents of a trip (CMR photos, delivery notes, receipts) uploaded by the driver or the office.
-- kind: CMR_PHOTO, DELIVERY_NOTE, RECEIPT, OTHER.
CREATE TABLE trip_file (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    trip_id             BIGINT        NOT NULL,
    stop_id             BIGINT        NULL,
    kind                VARCHAR(16)   NOT NULL,
    note                VARCHAR(500)  NULL,
    file_id             BIGINT        NOT NULL,
    uploaded_by         BIGINT        NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_trip_file_firma_trip (firma_id, trip_id),
    CONSTRAINT fk_trip_file_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_trip_file_trip_id FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_trip_file_stop_id FOREIGN KEY (stop_id) REFERENCES trip_stop (id),
    CONSTRAINT fk_trip_file_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_trip_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
