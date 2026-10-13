package com.energytracker.insightservice.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "insight")
public record InsightProperties(
                Integer maxDays,
                Integer defaultDays,
                String defaultProvider,
                String defaultModel,
                Prompt prompt,
                Generation generation,
                Map<String, Provider> providers,
                Deadline deadline,
                RateLimit rateLimit,
                Ai ai) {

        public int maxDaysOr(int fallback) {
                return maxDays != null ? maxDays : fallback;
        }

        public Deadline deadlineOr() {
                return deadline != null ? deadline : new Deadline(null, null, null);
        }

        public RateLimit rateLimitOr() {
                return rateLimit != null ? rateLimit : new RateLimit(null, null, null);
        }

        public Ai aiOr() {
                return ai != null ? ai : new Ai(null, null);
        }

        public record Prompt(Integer maxDevices) {
        }

        public record Generation(Double temperature, Integer maxOutputTokens) {
        }

        public record Provider(Boolean enabled, String displayName, List<Model> models) {
        }

        public record Model(String id, String displayName) {
        }

        public record Deadline(Integer serverBudgetMs, Integer firstTokenTimeoutMs, Integer slotWaitMs) {
                public long serverBudgetMsOr(int fallback) {
                        return serverBudgetMs != null ? serverBudgetMs : fallback;
                }

                public long firstTokenTimeoutMsOr(int fallback) {
                        return firstTokenTimeoutMs != null ? firstTokenTimeoutMs : fallback;
                }

                public long slotWaitMsOr(int fallback) {
                        return slotWaitMs != null ? slotWaitMs : fallback;
                }
        }

        public record RateLimit(Integer windowSeconds, Integer windowMax, Integer hourlyMax) {
                public int windowSecondsOr(int fallback) {
                        return windowSeconds != null ? windowSeconds : fallback;
                }

                public int windowMaxOr(int fallback) {
                        return windowMax != null ? windowMax : fallback;
                }

                public int hourlyMaxOr(int fallback) {
                        return hourlyMax != null ? hourlyMax : fallback;
                }
        }

        public record Ai(Integer maxConcurrent, Integer clientTimeoutMs) {
                public int maxConcurrentOr(int fallback) {
                        return maxConcurrent != null ? maxConcurrent : fallback;
                }

                public long clientTimeoutMsOr(int fallback) {
                        return clientTimeoutMs != null ? clientTimeoutMs : fallback;
                }
        }
}
