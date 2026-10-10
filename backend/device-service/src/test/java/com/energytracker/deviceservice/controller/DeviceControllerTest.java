package com.energytracker.deviceservice.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeviceControllerTest {

        private static final String BASE_PATH = "/api/v1/device";
        private static final String CREATE_PATH = BASE_PATH + "/create";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        /**
         * The FK fk_device_user refuses any user_id that is not in users, so tests
         * must use a real one. Requires the userservice `createUsers` test to have run.
         */
        private String anyExistingUserId() {
                return jdbcTemplate.queryForObject("""
                                SELECT user_id
                                FROM users
                                WHERE email LIKE 'device.user%@energytracker.dev'
                                LIMIT 1
                                """, String.class);
        }

        @Test
        void getDevice_whenMissing_returns404() throws Exception {
                mockMvc.perform(get(BASE_PATH + "/{deviceId}", 999_999L))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404))
                                .andExpect(jsonPath("$.detail").value("Device not found with id 999999"));
        }

        @Test
        void getDevice_whenIdIsNotANumber_returns400() throws Exception {
                mockMvc.perform(get(BASE_PATH + "/abc"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void createDevice_whenValid_returns201WithLocation() throws Exception {
                String userId = anyExistingUserId();

                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "name": "Living Room Light",
                                                  "type": "LIGHT",
                                                  "location": "Living Room",
                                                  "userId": "%s"
                                                }
                                                """.formatted(userId)))
                                .andExpect(status().isCreated())
                                .andExpect(header().string("Location",
                                                org.hamcrest.Matchers.matchesPattern(BASE_PATH + "/\\d+")))
                                .andExpect(jsonPath("$.deviceId").isNumber())
                                .andExpect(jsonPath("$.name").value("Living Room Light"))
                                .andExpect(jsonPath("$.type").value("LIGHT"))
                                .andExpect(jsonPath("$.userId").value(userId));
        }

        @Test
        void createDevice_whenUserIdDoesNotExist_returns409() throws Exception {
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "name": "Ghost Device",
                                                  "type": "LIGHT",
                                                  "location": "Nowhere",
                                                  "userId": "this-user-does-not-exist"
                                                }
                                                """))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void createDevice_whenRequiredFieldsMissing_returns400() throws Exception {
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                { "type": "LIGHT" }
                                                """))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void createDevice_whenBodyIsEmptyObject_returns400() throws Exception {
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void createDevice_whenTypeIsUnknownEnumValue_returns400() throws Exception {
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "name": "Lamp",
                                                  "type": "NOT_A_REAL_TYPE",
                                                  "location": "Bedroom",
                                                  "userId": "e5b1a4d0-0000-4000-8000-000000000001"
                                                }
                                                """))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void createDevice_whenBodyIsMalformedJson_returns400() throws Exception {
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ not json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void unsupportedMethodOnExistingPath_returns405WithSupportedMethods() throws Exception {
                mockMvc.perform(patch(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isMethodNotAllowed())
                                .andExpect(jsonPath("$.status").value(405))
                                .andExpect(jsonPath("$.supportedMethods").isArray());
        }

        @Test
        void postToUnknownPath_returns404() throws Exception {
                mockMvc.perform(post("/api/v1/no-such-endpoint")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void createDevice_whenContentTypeIsNotJson_returns415() throws Exception {
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("name=Lamp"))
                                .andExpect(status().isUnsupportedMediaType())
                                .andExpect(jsonPath("$.status").value(415));
        }
}
