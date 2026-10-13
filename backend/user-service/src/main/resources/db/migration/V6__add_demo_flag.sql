-- V6: Add demo flag to distinguish the seeded playground user from real users.
ALTER TABLE users
    ADD COLUMN demo TINYINT(1) NOT NULL DEFAULT 0;
