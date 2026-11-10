-- RUTERA schema, step 17: reference fuel prices per country for the trip calculator, shared by all firmas (no
-- firma_id), as in the fuel research of 2026-11-10 (fuel_price_ref). Per litre (kg for LNG/CNG) in the country's
-- currency: gross with VAT, net without VAT, the VAT rate and the excise; kind AVERAGE (national average, e.g. the EU
-- Weekly Oil Bulletin), CAP (official maximum price) or STATION_MEDIAN. Filled by price providers
-- (core.calc.FuelPriceProvider) or the operator; a firma's own fuel card purchases in a country come first.
CREATE TABLE fuel_price_ref (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    country             CHAR(2)       NOT NULL,
    product             VARCHAR(16)   NOT NULL,
    price_date          DATE          NOT NULL,
    gross               DECIMAL(12,4) NULL,
    net                 DECIMAL(12,4) NOT NULL,
    vat_rate            DECIMAL(5,2)  NULL,
    excise_per_1000l    DECIMAL(14,2) NULL,
    currency            CHAR(3)       NOT NULL,
    kind                VARCHAR(16)   NOT NULL,
    source              VARCHAR(100)  NOT NULL,
    fetched_at          DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fuel_price_ref (country, product, source, price_date),
    KEY ix_fuel_price_ref_lookup (country, product, price_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
