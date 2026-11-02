-- RUTERA schema, step 7: driver HR (group 3 of docs/DB_SCHEMA.md, second part): absences, manual working-day
-- entries, pay rates and payments.
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0); all rows are deleted physically.
-- Deviations from the sketch:
--   driver_day keeps only manual entries: the working-day calendar is computed on request from the tachograph
--   data and the absences, a manual entry overrides one day; driving_min / work_min / countries come from the
--   tachograph on request (countries with the places of the DDD files later);
--   driver_absence.approved_at is new (approved_by + approved_at); driver_pay_rate.per_km has 4 decimals.

-- Absence: SICK, VACATION, UNPAID, PARENTAL, OTHER. days = calendar days, both ends included.
CREATE TABLE driver_absence (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    from_date           DATE          NOT NULL,
    to_date             DATE          NOT NULL,
    days                INT           NOT NULL,
    paid                BOOLEAN       NOT NULL DEFAULT TRUE,
    note                VARCHAR(500)  NULL,
    file_id             BIGINT        NULL,
    approved_by         BIGINT        NULL,
    approved_at         DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_driver_absence_firma_driver_from (firma_id, driver_id, from_date),
    KEY ix_driver_absence_firma_from (firma_id, from_date),
    CONSTRAINT fk_driver_absence_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_absence_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id),
    CONSTRAINT fk_driver_absence_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_driver_absence_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Manual entry of one day in a driver's working calendar: WORK, REST, ABSENCE, HOLIDAY.
CREATE TABLE driver_day (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    day                 DATE          NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    note                VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_driver_day_driver_day (driver_id, day),
    KEY ix_driver_day_firma_driver (firma_id, driver_id, day),
    CONSTRAINT fk_driver_day_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_day_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Pay rates valid from a date (the newest rate not after a day applies); also read by the trip cost later.
CREATE TABLE driver_pay_rate (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    valid_from          DATE          NOT NULL,
    monthly_base        DECIMAL(14,2) NULL,
    per_diem_home       DECIMAL(14,2) NULL,
    per_diem_abroad     DECIMAL(14,2) NULL,
    per_km              DECIMAL(10,4) NULL,
    currency            CHAR(3)       NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_driver_pay_rate_driver_from (driver_id, valid_from),
    KEY ix_driver_pay_rate_firma_driver (firma_id, driver_id),
    CONSTRAINT fk_driver_pay_rate_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_pay_rate_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Payment for a period (YYYY-MM): SALARY, PER_DIEM, BONUS, ADVANCE, DEDUCTION. Visible to owner and accountant only.
CREATE TABLE driver_payment (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    period              CHAR(7)       NOT NULL,
    kind                VARCHAR(32)   NOT NULL,
    amount              DECIMAL(14,2) NOT NULL,
    currency            CHAR(3)       NOT NULL,
    paid_at             DATE          NULL,
    note                VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_driver_payment_firma_period (firma_id, period),
    KEY ix_driver_payment_firma_driver (firma_id, driver_id, period),
    CONSTRAINT fk_driver_payment_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_payment_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
