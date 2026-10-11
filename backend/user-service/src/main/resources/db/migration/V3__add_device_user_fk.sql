-- V3: enforce the one-to-many relationship users 1 ───< device
--      a device.user_id must reference an existing users.user_id
--      deleting a user who still owns devices is blocked (ON DELETE RESTRICT)
ALTER TABLE device
    ADD CONSTRAINT fk_device_user
    FOREIGN KEY (user_id) REFERENCES users (user_id)
    ON DELETE RESTRICT
    ON UPDATE RESTRICT;
