-- RUTERA schema, step 21: drivers' written explanations of tachograph infringements (oświadczenie kierowcy). One per
-- infringement: created by the office or automatically when the import finds a new violation; the driver picks a
-- reason and signs in the cabinet; the signed PDF (template in the language of the firma's country) is kept in
-- stored_file. The infringement's data is copied, because violations are recomputed after every import; violation_id
-- is cleared when its violation disappears.
CREATE TABLE driver_explanation (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    driver_id           BIGINT        NOT NULL,
    violation_id        BIGINT        NULL,
    rule_code           VARCHAR(32)   NULL,
    vehicle_plate       VARCHAR(16)   NULL,
    started_at          DATETIME(6)   NOT NULL,
    ended_at            DATETIME(6)   NULL,
    actual_min          INT           NULL,
    limit_min           INT           NULL,
    exceeded_min        INT           NULL,
    status              VARCHAR(16)   NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    reason              VARCHAR(32)   NULL,
    reason_text         VARCHAR(1000) NULL,
    place               VARCHAR(100)  NULL,
    doc_lang            VARCHAR(5)    NULL,
    signed_at           DATETIME(6)   NULL,
    signed_by           BIGINT        NULL,
    signature_file_id   BIGINT        NULL,
    pdf_file_id         BIGINT        NULL,
    cancelled_at        DATETIME(6)   NULL,
    cancelled_by        BIGINT        NULL,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    updated_at          DATETIME(6)   NULL,
    updated_by          BIGINT        NULL,
    version             INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY ux_driver_explanation_infringement (driver_id, rule_code, started_at),
    KEY ix_driver_explanation_firma_status (firma_id, status),
    KEY ix_driver_explanation_violation (violation_id),
    CONSTRAINT fk_driver_explanation_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id),
    CONSTRAINT fk_driver_explanation_driver_id FOREIGN KEY (driver_id) REFERENCES driver (id),
    CONSTRAINT fk_driver_explanation_signature FOREIGN KEY (signature_file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_driver_explanation_pdf FOREIGN KEY (pdf_file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
