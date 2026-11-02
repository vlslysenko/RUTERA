-- RUTERA schema, step 8: invoices, payments and debt reminders (group 6 of docs/DB_SCHEMA.md). Recording invoices
-- (MVP); issuing and KSeF (stage 3) use the same tables.
-- Conventions as in V1 (docs/DB_SCHEMA.md section 0); all rows are deleted physically.
-- Deviations from the sketch:
--   the order link is transport_order.invoice_id (several orders on one invoice) instead of invoice.order_id;
--   invoice.status is not stored: it follows from the payments, the due date and the cancelled flag (cancelled);
--   payment is named invoice_payment (driver_payment exists, "payment" alone is too generic);
--   reminder_schedule carries its steps as JSON (offset_days, escalate) instead of a reminder_step table, and
--   reminder_sent.offset_days replaces step_id; contractor.reminder_schedule_id and currency_rate come later.

-- Invoice: direction SALES (receivables) or PURCHASE; source MANUAL (IMPORT, ISSUED later). Number unique per firma
-- and direction.
CREATE TABLE invoice (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    direction           VARCHAR(16)   NOT NULL,
    number              VARCHAR(64)   NOT NULL,
    contractor_id       BIGINT        NOT NULL,
    issue_date          DATE          NOT NULL,
    due_date            DATE          NOT NULL,
    currency            CHAR(3)       NOT NULL,
    net                 DECIMAL(14,2) NOT NULL,
    vat                 DECIMAL(14,2) NOT NULL,
    gross               DECIMAL(14,2) NOT NULL,
    paid_amount         DECIMAL(14,2) NOT NULL DEFAULT 0,
    cancelled           BOOLEAN       NOT NULL DEFAULT FALSE,
    trip_id             BIGINT        NULL,
    file_id             BIGINT        NULL,
    source              VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',
    ksef_id             VARCHAR(64)   NULL,
    reminders_on        BOOLEAN       NOT NULL DEFAULT TRUE,
    notes               VARCHAR(2000) NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_invoice_firma_direction_number (firma_id, direction, number),
    KEY ix_invoice_firma_issue (firma_id, issue_date),
    KEY ix_invoice_firma_due (firma_id, due_date),
    KEY ix_invoice_firma_contractor (firma_id, contractor_id),
    CONSTRAINT fk_invoice_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_invoice_contractor_id FOREIGN KEY (contractor_id) REFERENCES contractor (id),
    CONSTRAINT fk_invoice_trip_id FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_invoice_file_id FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Line item. vat_code: RATE (vat_rate in percent, 23.00), ZW (exempt), NP (not taxable here, e.g. international
-- transport services), OO (reverse charge); without RATE the line has no VAT.
CREATE TABLE invoice_line (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    invoice_id          BIGINT        NOT NULL,
    seq                 INT           NOT NULL,
    description         VARCHAR(500)  NOT NULL,
    qty                 DECIMAL(14,3) NOT NULL,
    unit                VARCHAR(16)   NULL,
    unit_price          DECIMAL(14,4) NOT NULL,
    vat_code            VARCHAR(8)    NOT NULL DEFAULT 'RATE',
    vat_rate            DECIMAL(5,2)  NULL,
    net                 DECIMAL(14,2) NOT NULL,
    vat                 DECIMAL(14,2) NOT NULL,
    gross               DECIMAL(14,2) NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_invoice_line_firma_invoice (firma_id, invoice_id, seq),
    CONSTRAINT fk_invoice_line_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_invoice_line_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoice (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Payment against an invoice: TRANSFER, CASH, OFFSET; in the invoice currency.
CREATE TABLE invoice_payment (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    invoice_id          BIGINT        NOT NULL,
    paid_at             DATE          NOT NULL,
    amount              DECIMAL(14,2) NOT NULL,
    currency            CHAR(3)       NOT NULL,
    method              VARCHAR(16)   NOT NULL,
    reference           VARCHAR(128)  NULL,
    note                VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_invoice_payment_firma_invoice (firma_id, invoice_id),
    CONSTRAINT fk_invoice_payment_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_invoice_payment_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoice (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Reminder plan of the firma (one default for now). steps: JSON array of {offsetDays, escalate}.
CREATE TABLE reminder_schedule (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    name                VARCHAR(100)  NOT NULL,
    is_default          BOOLEAN       NOT NULL DEFAULT TRUE,
    send_hour           INT           NOT NULL DEFAULT 9,
    cc_emails           JSON          NULL,
    stop_when_paid      BOOLEAN       NOT NULL DEFAULT TRUE,
    steps               JSON          NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_reminder_schedule_firma (firma_id, is_default),
    CONSTRAINT fk_reminder_schedule_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Reminder of an invoice: QUEUED, SENT, FAILED. offset_days NULL = sent by hand. The e-mail goes through email_outbox.
CREATE TABLE reminder_sent (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    invoice_id          BIGINT        NOT NULL,
    offset_days         INT           NULL,
    sent_at             DATETIME(6)   NULL,
    to_emails           VARCHAR(1000) NOT NULL,
    subject             VARCHAR(255)  NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    outbox_id           BIGINT        NULL,
    error               VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_reminder_sent_firma_invoice (firma_id, invoice_id),
    CONSTRAINT fk_reminder_sent_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_reminder_sent_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoice (id),
    CONSTRAINT fk_reminder_sent_outbox_id FOREIGN KEY (outbox_id) REFERENCES email_outbox (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE transport_order
    ADD COLUMN invoice_id BIGINT NULL,
    ADD KEY ix_transport_order_firma_invoice (firma_id, invoice_id),
    ADD CONSTRAINT fk_transport_order_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoice (id);
