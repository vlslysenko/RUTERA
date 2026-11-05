-- RUTERA schema, step 16: which deadline reminders were already sent by Web Push (driver documents, driver cards,
-- vehicle documents). One row per item, expiry date and lead time, so a reminder goes out once per threshold and a
-- renewed document (new valid_to) starts again. item_kind: DRIVER_DOCUMENT, DRIVER_CARD, VEHICLE_DOCUMENT. No foreign
-- key to the items: documents are deleted physically; rows are removed some time after valid_to by the job itself.
CREATE TABLE push_deadline_sent (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    firma_id            BIGINT        NOT NULL,
    item_kind           VARCHAR(16)   NOT NULL,
    item_id             BIGINT        NOT NULL,
    valid_to            DATE          NOT NULL,
    threshold_days      INT           NOT NULL,
    sent_at             DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_push_deadline_sent_item (item_kind, item_id, valid_to, threshold_days),
    KEY ix_push_deadline_sent_valid_to (valid_to),
    CONSTRAINT fk_push_deadline_sent_firma_id FOREIGN KEY (firma_id) REFERENCES firma (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
