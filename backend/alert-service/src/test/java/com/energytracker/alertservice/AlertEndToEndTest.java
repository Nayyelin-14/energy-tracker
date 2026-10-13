package com.energytracker.alertservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.web.client.RestTemplate;

import com.energytracker.alertservice.entity.Alert;
import com.energytracker.alertservice.repository.AlertRepository;
import com.energytracker.kafka.event.AlertingEvent;

@SpringBootTest
class AlertEndToEndTest {

        private static final String TOPIC = "energy-alerts";
        private static final long TEST_USER_ID = 7108746548581385587L; // seeded by user-service tests

        @Autowired
        private AlertRepository alertRepository;

        @Test
        void alertEventIsPersistedAndEmailSent() throws Exception {
                // same producer setup as usage-service (JsonSerializer adds the __TypeId__
                // header)
                final Map<String, Object> producerProps = Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094",
                                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
                final KafkaTemplate<String, AlertingEvent> kafkaTemplate = new KafkaTemplate<>(
                                new DefaultKafkaProducerFactory<>(producerProps));

                final String uniqueEmail = "e2e-" + System.currentTimeMillis() + "@example.com";

                final AlertingEvent event = AlertingEvent.builder()
                                .userId(TEST_USER_ID)
                                .message("E2E test alert")
                                .threshold(10.0)
                                .energyConsumed(12.5)
                                .email(uniqueEmail)
                                .build();

                kafkaTemplate.send(TOPIC, event).get();

                // wait for the listener to process (persist + send email)
                Alert alert = null;
                for (int i = 0; i < 60; i++) {
                        Thread.sleep(500);
                        final List<Alert> matches = alertRepository.findAll().stream()
                                        .filter(a -> uniqueEmail.equals(a.getEmail()))
                                        .toList();
                        if (!matches.isEmpty() && Boolean.TRUE.equals(matches.get(0).getSent())) {
                                alert = matches.get(0);
                                break;
                        }
                }

                assertThat(alert).isNotNull();
                assertThat(alert.getUserId()).isEqualTo(TEST_USER_ID);
                assertThat(alert.getMessage()).isEqualTo("E2E test alert");
                assertThat(alert.getThreshold()).isEqualTo(10.0);
                assertThat(alert.getEnergyConsumed()).isEqualTo(12.5);
                assertThat(alert.getSent()).isTrue();

                // verify Mailpit received the email
                final RestTemplate rest = new RestTemplate();
                @SuppressWarnings("unchecked")
                final Map<String, Object> inbox = rest.getForObject("http://localhost:8025/api/v1/messages",
                                Map.class);
                assertThat(inbox).isNotNull();
                assertThat((int) inbox.get("total")).isGreaterThan(0);
        }
}
