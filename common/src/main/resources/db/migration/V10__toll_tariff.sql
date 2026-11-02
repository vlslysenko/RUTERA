-- RUTERA schema, step 10: toll tariffs of the trip calculator (group 5 of docs/DB_SCHEMA.md).
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0); rows are deleted physically.
-- Deviation from the sketch: the table is per firma (each firma keeps its own rates) instead of one table shared by
-- all firmas; road_class is not used yet (the rate is the average per km over all km in the country).

-- Rate per km in a country, optionally only for a Euro class, from a number of axles or a gross weight on, valid from a
-- date. The most specific matching row wins, then the latest valid_from.
CREATE TABLE toll_tariff (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    country             CHAR(2)       NOT NULL,
    euro_class          VARCHAR(16)   NULL,
    axles_min           INT           NULL,
    weight_min_kg       INT           NULL,
    rate_per_km         DECIMAL(10,4) NOT NULL,
    currency            CHAR(3)       NOT NULL,
    valid_from          DATE          NULL,
    notes               VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_toll_tariff_firma_country (firma_id, country),
    CONSTRAINT fk_toll_tariff_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
