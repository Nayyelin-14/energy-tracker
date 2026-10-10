package com.energytracker.userservice.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// NOT @Transactional: the FK violation only surfaces when the delete actually
// flushes and commits, which an outer test transaction would defer until after
// the assertion. Both tests below are non-mutating, so nothing to roll back.
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

        private static final String BASE_PATH = "/api/v1/user";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test
        void deleteUser_whenUserStillOwnsDevices_returns409() throws Exception {
                String userId = jdbcTemplate.queryForObject("""
                                SELECT u.user_id
                                FROM users u
                                JOIN device d ON d.user_id = u.user_id
                                LIMIT 1
                                """, String.class);

                mockMvc.perform(delete(BASE_PATH + "/{userId}", userId))
                                .andExpect(status().isConflict());
        }

        @Test
        void deleteUser_whenUserDoesNotExist_returns404() throws Exception {
                mockMvc.perform(delete(BASE_PATH + "/{userId}", "no-such-user"))
                                .andExpect(status().isNotFound());
        }
}
