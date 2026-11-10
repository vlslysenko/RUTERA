-- RUTERA schema, step 17: fuel prices per country for the trip calculator, shared by all firmas (no firma_id).
-- Net price per litre (or kg) without VAT on the day valid_on; the calculator takes the latest row on or before the
-- trip's start. Filled by the operator or by a price provider (core.calc.FuelPriceProvider); a firma's own fuel card
-- prices in a country come first.
CREATE TABLE fuel_price (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    country             CHAR(2)       NOT NULL,
    product             VARCHAR(16)   NOT NULL,
    price_net           DECIMAL(10,4) NOT NULL,
    currency            CHAR(3)       NOT NULL,
    valid_on            DATE          NOT NULL,
    source              VARCHAR(200)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fuel_price_country_product_day (country, product, valid_on)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
