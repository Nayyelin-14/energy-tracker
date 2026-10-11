-- V4: user_id and device_id become BIGINT values derived from a random UUID
--      (63 most-significant bits, sign bit cleared -> always positive).
--
-- !!! DESTRUCTIVE MIGRATION !!!
-- This migration DROPs and re-creates the `users` and `device` tables.
-- It is intended for DEVELOPMENT / TEST databases only. Do not run it against
-- production data. All existing rows are discarded; ids from V1-V3 (UUID
-- strings for users, AUTO_INCREMENT numbers for devices) are not carried over.
--
-- Foreign-key dependencies handled here:
--   * fk_device_user (device.user_id -> users.user_id) from V3:
--     `device` is dropped first, then `users`, with FOREIGN_KEY_CHECKS
--     disabled for the duration so the drop order cannot fail.

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS device;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    user_id                   BIGINT       NOT NULL,
    email                     VARCHAR(255) NOT NULL,
    password                  VARCHAR(255) NOT NULL,
    name                      VARCHAR(255) NULL,
    address                   VARCHAR(255) NULL,
    alerting                  BIT(1)       NOT NULL,
    energy_alerting_threshold DOUBLE       NOT NULL,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE device (
    device_id                   BIGINT       NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    type                        ENUM('CAMERA', 'DOORBELL', 'LIGHT', 'LOCK', 'SPEAKER', 'THERMOSTAT') NOT NULL,
    location                    VARCHAR(255) NULL,
    user_id                     BIGINT       NOT NULL,
    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL,
    PRIMARY KEY (device_id),
    INDEX idx_device_user_id (user_id),
    CONSTRAINT fk_device_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
