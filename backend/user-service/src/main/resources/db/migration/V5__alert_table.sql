CREATE TABLE alert (
    alert_id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    message         VARCHAR(255),
    threshold       DOUBLE,
    energy_consumed DOUBLE,
    email           VARCHAR(255),
    sent            TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (alert_id),
    CONSTRAINT fk_alert_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;