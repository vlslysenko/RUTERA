-- RUTERA schema, step 19: exchange rates for the trip calculator's total in the firma's base currency, shared by all
-- firmas: units of the currency per 1 EUR on a day (ECB euro foreign exchange reference rates; NBP later for Polish
-- accounting). Filled daily by core.calc.EcbRates; started with the ECB rates of 2026-11-02, the day of the starting
-- fuel prices (source: European Central Bank, free reuse with the source named).
CREATE TABLE fx_rate (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    rate_date           DATE          NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    currency            CHAR(3)       NOT NULL,
    rate                DECIMAL(18,8) NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_fx_rate (source, currency, rate_date),
    KEY ix_fx_rate_lookup (currency, rate_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO fx_rate (rate_date, source, currency, rate, created_at, version) VALUES
    ('2026-11-02', 'ECB', 'USD', 1.1216, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'JPY', 177.1, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'CZK', 24.351, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'DKK', 7.4737, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'GBP', 0.8358, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'HUF', 364.5, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'PLN', 4.4185, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'RON', 5.3405, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'SEK', 10.9475, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'CHF', 0.9193, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'ISK', 136.2, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'NOK', 10.3775, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'TRY', 56.0442, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'AUD', 1.6004, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'BRL', 6.099, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'CAD', 1.5673, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'CNY', 7.4567, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'HKD', 8.8002, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'IDR', 20341.45, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'ILS', 3.521, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'INR', 106.4505, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'KRW', 1506.2, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'MXN', 20.9963, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'MYR', 4.5655, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'NZD', 1.9934, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'PHP', 71.274, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'SGD', 1.4344, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'THB', 37.865, '2026-11-10 15:10:00', 0),
    ('2026-11-02', 'ECB', 'ZAR', 18.6619, '2026-11-10 15:10:00', 0);
