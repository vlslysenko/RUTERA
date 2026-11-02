-- RUTERA schema, step 5: trips and orders (group 5 of docs/DB_SCHEMA.md), first part: contractor,
-- transport_order, trip, trip_driver, trip_stop. CMR documents, trip costs, the trip calculator and toll tariffs
-- come in later steps.
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0). Soft delete on contractor (a dictionary); orders, trips and
-- their crew and stops are deleted physically.
-- Deviations from the sketch:
--   contractor.reminder_schedule_id comes with the invoices (group 6); contractor.notes is new;
--   the VAT id is unique among the non-deleted contractors of a firma (generated column vat_key);
--   transport_order keeps the loading and unloading place of the order itself (loading_*, unloading_*), so an
--   order without a trip already says where the cargo goes; customer_ref is the customer's own order number;
--   trip.cost_fuel / cost_tolls / cost_driver / currency come with the cost calculation;
--   trip_stop.status is PLANNED, DONE or SKIPPED; trip_stop.kind has OTHER in addition.

-- Customer, subcontracted carrier or supplier. invoice_emails: JSON array of addresses (reminders, invoices).
CREATE TABLE contractor (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    name                VARCHAR(200)  NOT NULL,
    vat_id              VARCHAR(32)   NULL,
    country             CHAR(2)       NOT NULL,
    address             VARCHAR(500)  NULL,
    phone               VARCHAR(40)   NULL,
    invoice_emails      JSON          NULL,
    payment_days        INT           NULL,
    lang                VARCHAR(5)    NULL,
    is_customer         BOOLEAN       NOT NULL DEFAULT TRUE,
    is_carrier          BOOLEAN       NOT NULL DEFAULT FALSE,
    notes               VARCHAR(2000) NULL,
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    vat_key             VARCHAR(32)   GENERATED ALWAYS AS (IF(deleted, NULL, vat_id)) STORED,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_contractor_firma_vat_key (firma_id, vat_key),
    KEY ix_contractor_firma_name (firma_id, name),
    CONSTRAINT fk_contractor_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Trip: PLANNED, RUNNING, DONE, CANCELLED. One trip may carry several orders.
CREATE TABLE trip (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    number              VARCHAR(32)   NOT NULL,
    vehicle_id          BIGINT        NOT NULL,
    trailer_id          BIGINT        NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'PLANNED',
    planned_start       DATETIME(6)   NULL,
    planned_end         DATETIME(6)   NULL,
    actual_start        DATETIME(6)   NULL,
    actual_end          DATETIME(6)   NULL,
    km_planned          INT           NULL,
    km_actual           INT           NULL,
    notes               VARCHAR(2000) NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_trip_firma_number (firma_id, number),
    KEY ix_trip_firma_planned (firma_id, planned_start),
    KEY ix_trip_firma_vehicle (firma_id, vehicle_id),
    CONSTRAINT fk_trip_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_trip_vehicle_id FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_trip_trailer_id FOREIGN KEY (trailer_id) REFERENCES vehicle (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Customer order: NEW, PLANNED, IN_PROGRESS, DONE, INVOICED, CANCELLED (derived from the trip except CANCELLED and
-- INVOICED). source: MANUAL (EXCHANGE with the freight exchanges, stage 3).
CREATE TABLE transport_order (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    number              VARCHAR(32)   NOT NULL,
    order_date          DATE          NOT NULL,
    contractor_id       BIGINT        NOT NULL,
    customer_ref        VARCHAR(64)   NULL,
    price               DECIMAL(14,2) NULL,
    currency            CHAR(3)       NULL,
    cargo               VARCHAR(500)  NULL,
    weight_kg           INT           NULL,
    loading_place       VARCHAR(200)  NOT NULL,
    loading_country     CHAR(2)       NOT NULL,
    loading_date        DATE          NULL,
    unloading_place     VARCHAR(200)  NOT NULL,
    unloading_country   CHAR(2)       NOT NULL,
    unloading_date      DATE          NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'NEW',
    trip_id             BIGINT        NULL,
    source              VARCHAR(32)   NOT NULL DEFAULT 'MANUAL',
    external_ref        VARCHAR(64)   NULL,
    notes               VARCHAR(2000) NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_transport_order_firma_number (firma_id, number),
    KEY ix_transport_order_firma_date (firma_id, order_date),
    KEY ix_transport_order_firma_contractor (firma_id, contractor_id),
    KEY ix_transport_order_firma_trip (firma_id, trip_id),
    CONSTRAINT fk_transport_order_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_transport_order_contractor_id FOREIGN KEY (contractor_id) REFERENCES contractor (id),
    CONSTRAINT fk_transport_order_trip_id FOREIGN KEY (trip_id) REFERENCES trip (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Crew of a trip: MAIN, SECOND (two drivers for Regulation 561/2006).
CREATE TABLE trip_driver (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    trip_id             BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    role                VARCHAR(32)   NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_trip_driver_trip_role (trip_id, role),
    UNIQUE KEY ux_trip_driver_trip_driver (trip_id, driver_id),
    KEY ix_trip_driver_firma_driver (firma_id, driver_id),
    CONSTRAINT fk_trip_driver_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_trip_driver_trip_id FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_trip_driver_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Stops of a trip in order (seq): LOADING, UNLOADING, BORDER, REST, FUEL, CUSTOMS, OTHER; status PLANNED, DONE,
-- SKIPPED (the driver cabinet will set actual_at and the status).
CREATE TABLE trip_stop (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    trip_id             BIGINT        NOT NULL,
    order_id            BIGINT        NULL,
    seq                 INT           NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    name                VARCHAR(200)  NULL,
    address             VARCHAR(500)  NOT NULL,
    country             CHAR(2)       NOT NULL,
    lat                 DECIMAL(9,6)  NULL,
    lng                 DECIMAL(9,6)  NULL,
    planned_at          DATETIME(6)   NULL,
    actual_at           DATETIME(6)   NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'PLANNED',
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_trip_stop_firma_trip (firma_id, trip_id, seq),
    CONSTRAINT fk_trip_stop_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_trip_stop_trip_id FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_trip_stop_order_id FOREIGN KEY (order_id) REFERENCES transport_order (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
