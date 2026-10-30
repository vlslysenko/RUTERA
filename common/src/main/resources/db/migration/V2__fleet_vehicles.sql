-- RUTERA schema, step 2: fleet (group 2 of docs/DB_SCHEMA.md), first part: vehicle and vehicle_document.
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0). Soft delete on vehicle (a dictionary); documents are
-- deleted physically.
-- Deviations from the sketch:
--   vehicle.gps_device_id is not created yet: gps_device is stage 2 and comes with it;
--   vehicle.default_driver_id has no foreign key yet: the driver table (group 3) adds it in its migration;
--   the plate is unique among the non-deleted vehicles of a firma, compared without case, spaces and hyphens
--   (generated column plate_key, NULL once deleted), so deleting a vehicle frees its plate.

-- Vehicle or trailer. kind: TRUCK, TRACTOR, VAN, TRAILER, SEMI_TRAILER (trailers are rows here as well,
-- default_trailer_id points into the same table). euro_class: EURO_3 .. EURO_6; fuel_type: DIESEL, HVO, LNG,
-- CNG, PETROL, ELECTRIC; tacho_gen: NONE, GEN1, GEN2; status: ACTIVE, SERVICE, INACTIVE.
-- last_vu_download_at is written by the tachograph import (group 4), not by the vehicle form.
CREATE TABLE vehicle (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    plate               VARCHAR(20)   NOT NULL,
    vin                 VARCHAR(17)   NULL,
    make                VARCHAR(60)   NULL,
    model               VARCHAR(60)   NULL,
    euro_class          VARCHAR(32)   NULL,
    axles               INT           NULL,
    gross_weight_kg     INT           NULL,
    height_mm           INT           NULL,
    avg_l_100km         DECIMAL(5,2)  NULL,
    fuel_type           VARCHAR(32)   NULL,
    tacho_gen           VARCHAR(32)   NOT NULL DEFAULT 'NONE',
    odometer_km         INT           NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    default_driver_id   BIGINT        NULL,
    default_trailer_id  BIGINT        NULL,
    last_vu_download_at DATETIME(6)   NULL,
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    plate_key           VARCHAR(20)   GENERATED ALWAYS AS
                            (IF(deleted, NULL, REPLACE(REPLACE(UPPER(plate), ' ', ''), '-', ''))) STORED,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_vehicle_firma_plate_key (firma_id, plate_key),
    KEY ix_vehicle_firma_plate (firma_id, plate),
    KEY ix_vehicle_firma_vin (firma_id, vin),
    CONSTRAINT fk_vehicle_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_vehicle_default_trailer_id FOREIGN KEY (default_trailer_id) REFERENCES vehicle (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Document with a deadline. kind: REGISTRATION, INSURANCE_OC, INSURANCE_AC, INSPECTION, TACHO_CALIBRATION,
-- LICENSE_COPY, TOLL_DEVICE, OTHER. Within remind_days before valid_to it turns into "needs attention".
CREATE TABLE vehicle_document (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    vehicle_id          BIGINT        NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    number              VARCHAR(64)   NULL,
    valid_from          DATE          NULL,
    valid_to            DATE          NULL,
    cost                DECIMAL(14,2) NULL,
    currency            CHAR(3)       NULL,
    file_id             BIGINT        NULL,
    remind_days         INT           NOT NULL DEFAULT 30,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_vehicle_document_firma_vehicle (firma_id, vehicle_id),
    KEY ix_vehicle_document_firma_valid_to (firma_id, valid_to),
    CONSTRAINT fk_vehicle_document_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_vehicle_document_vehicle_id FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_vehicle_document_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
