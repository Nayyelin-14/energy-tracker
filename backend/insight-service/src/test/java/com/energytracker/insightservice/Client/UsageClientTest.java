package com.energytracker.insightservice.Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.energytracker.insightservice.dto.UsageCoverageDto;

class UsageClientTest {

        private static final String BASE_URL = "http://localhost:8103/api/v1/usage";

        private final RestTemplate restTemplate = new RestTemplate();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        private final UsageClient usageClient = new UsageClient(restTemplate, BASE_URL);

        @Test
        void parsesCoverageResponse() {
                server.expect(once(), requestTo(BASE_URL + "/1/coverage"))
                                .andExpect(method(GET))
                                .andRespond(withSuccess("""
                                                {
                                                  "userId": 1,
                                                  "firstDataDay": "2026-09-19",
                                                  "lastDataDay": "2026-10-12",
                                                  "maxDays": 24,
                                                  "daysWithData": 18,
                                                  "deviceCount": 2,
                                                  "devicesWithNoData": [5],
                                                  "hasAnyData": true
                                                }
                                                """, MediaType.APPLICATION_JSON));

                UsageCoverageDto coverage = usageClient.getCoverage(1L);

                assertThat(coverage.userId()).isEqualTo(1L);
                assertThat(coverage.firstDataDay()).isEqualTo(LocalDate.of(2026, 9, 19));
                assertThat(coverage.lastDataDay()).isEqualTo(LocalDate.of(2026, 10, 12));
                assertThat(coverage.maxReportableDays()).isEqualTo(24);
                assertThat(coverage.daysWithData()).isEqualTo(18);
                assertThat(coverage.deviceCount()).isEqualTo(2);
                assertThat(coverage.devicesWithNoData()).containsExactly(5L);
                assertThat(coverage.hasData()).isTrue();
                server.verify();
        }

        @Test
        void parsesEmptyCoverageResponse() {
                server.expect(once(), requestTo(BASE_URL + "/2/coverage"))
                                .andRespond(withSuccess("""
                                                {
                                                  "userId": 2,
                                                  "hasAnyData": false,
                                                  "maxDays": 0,
                                                  "daysWithData": 0,
                                                  "deviceCount": 0,
                                                  "devicesWithNoData": []
                                                }
                                                """, MediaType.APPLICATION_JSON));

                UsageCoverageDto coverage = usageClient.getCoverage(2L);

                assertThat(coverage.hasData()).isFalse();
                assertThat(coverage.maxReportableDays()).isZero();
                server.verify();
        }
}
