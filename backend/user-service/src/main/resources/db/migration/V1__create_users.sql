-- V1: users table (initial schema, taken over from Hibernate ddl-auto)
CREATE TABLE users (
    user_id                   VARCHAR(255) NOT NULL,
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
