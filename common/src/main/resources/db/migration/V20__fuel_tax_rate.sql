-- RUTERA schema, step 20: the tax calendar of fuels, shared by all firmas: the VAT rate and the excise per 1000 l (in
-- the country's currency) from valid_from to valid_to. The trip calculator moves a past net price to the excise of the
-- trip's day, so a trip after a temporary tax cut ends is priced with the tax that applies then.
-- Started with the temporary measures of 2026 that end on known days (fuel research of 2026-11-10, sources: DKV
-- "measures in the fuel market" and the EU Weekly Oil Bulletin duties of 2026-11-02); the level after a measure is the
-- level during it plus the cut.
CREATE TABLE fuel_tax_rate (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    country             CHAR(2)       NOT NULL,
    product             VARCHAR(16)   NOT NULL,
    vat_rate            DECIMAL(5,2)  NULL,
    excise_per_1000l    DECIMAL(14,2) NULL,
    currency            CHAR(3)       NOT NULL,
    valid_from          DATE          NOT NULL,
    valid_to            DATE          NULL,
    source              VARCHAR(100)  NOT NULL,
    note                VARCHAR(300)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fuel_tax_rate (country, product, valid_from),
    KEY ix_fuel_tax_rate_lookup (country, product, valid_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO fuel_tax_rate (country, product, vat_rate, excise_per_1000l, currency, valid_from, valid_to, source, note,
                           created_at, version) VALUES
    ('PL', 'DIESEL', 8, 1413.52, 'PLN', '2026-10-03', '2026-12-31', 'RESEARCH_2026',
     'VAT 8 % instead of 23 % and excise cut 0.28 PLN/l until 2026-12-31', '2026-11-10 16:00:00', 0),
    ('PL', 'DIESEL', 23, 1693.52, 'PLN', '2027-01-01', NULL, 'RESEARCH_2026',
     'after the cuts end: VAT 23 %, excise + 0.28 PLN/l', '2026-11-10 16:00:00', 0),
    ('DE', 'DIESEL', 19, 330.00, 'EUR', '2026-10-01', '2026-12-31', 'RESEARCH_2026',
     'energy tax cut 14.04 ct/l until 2026-12-31', '2026-11-10 16:00:00', 0),
    ('DE', 'DIESEL', 19, 470.40, 'EUR', '2027-01-01', NULL, 'RESEARCH_2026',
     'after the cut ends: + 14.04 ct/l', '2026-11-10 16:00:00', 0),
    ('AT', 'DIESEL', 20, 330.00, 'EUR', '2026-10-01', '2026-11-30', 'RESEARCH_2026',
     'mineral oil tax cut 6.7 ct/l (fuel price brake) until 2026-11-30', '2026-11-10 16:00:00', 0),
    ('AT', 'DIESEL', 20, 397.00, 'EUR', '2026-12-01', NULL, 'RESEARCH_2026',
     'after the cut ends: + 6.7 ct/l', '2026-11-10 16:00:00', 0),
    ('SE', 'DIESEL', 25, 1561.00, 'SEK', '2026-07-01', '2026-11-30', 'RESEARCH_2026',
     'CO2 tax cut 2.40 SEK/l until 2026-11-30', '2026-11-10 16:00:00', 0),
    ('SE', 'DIESEL', 25, 3961.00, 'SEK', '2026-12-01', NULL, 'RESEARCH_2026',
     'after the cut ends: + 2.40 SEK/l', '2026-11-10 16:00:00', 0);
