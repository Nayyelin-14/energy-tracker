package com.energytracker.insightservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.energytracker.insightservice.exception.InsightProviderException;

class InsightDeadlineTest {

        @Test
        void returnsResultWithinBudget() {
                InsightDeadline deadline = InsightDeadline.start(5_000);

                String result = deadline.execute("stage", () -> "done");

                assertThat(result).isEqualTo("done");
        }

        @Test
        void propagatesWorkExceptionUnchanged() {
                InsightDeadline deadline = InsightDeadline.start(5_000);

                assertThatThrownBy(() -> deadline.execute("stage", () -> {
                                throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class)
                                .hasMessage("boom");
        }

        @Test
        void timesOutWithGatewayTimeout() {
                InsightDeadline deadline = InsightDeadline.start(100);

                assertThatThrownBy(() -> deadline.execute("generation", () -> {
                                Thread.sleep(5_000);
                                return "too late";
                })).isInstanceOf(InsightProviderException.class)
                                .satisfies(error -> assertThat(((InsightProviderException) error).status())
                                                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT))
                                .hasMessageContaining("generation")
                                .hasMessageContaining("deadline");
        }

        @Test
        void exhaustedBudgetFailsImmediatelyWithoutRunningWork() throws InterruptedException {
                AtomicBoolean ran = new AtomicBoolean(false);
                InsightDeadline deadline = InsightDeadline.start(1);

                Thread.sleep(5);
                assertThatThrownBy(() -> deadline.execute("usage lookup", () -> {
                                ran.set(true);
                                return "never";
                })).isInstanceOf(InsightProviderException.class)
                                .hasMessageContaining("usage lookup");

                assertThat(ran).isFalse();
        }
}
