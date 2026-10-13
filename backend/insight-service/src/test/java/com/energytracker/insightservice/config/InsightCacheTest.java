package com.energytracker.insightservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.energytracker.insightservice.service.InsightService.CachedInsight;

class InsightCacheTest {

        private static final String KEY = "gemini:gemini-2.5-flash:3:SAVING_TIPS:1:fixturehash";

        private ValueOperations<String, String> valueOperations;
        private Map<String, String> store;
        private InsightCache cache;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
                StringRedisTemplate redis = mock(StringRedisTemplate.class);
                valueOperations = mock(ValueOperations.class);
                store = new HashMap<>();

                doReturn(valueOperations).when(redis).opsForValue();
                doAnswer(invocation -> store.get(invocation.<String>getArgument(0)))
                                .when(valueOperations).get(anyString());
                doAnswer(invocation -> {
                        store.put(invocation.<String>getArgument(0),
                                        invocation.<String>getArgument(1));
                        return null;
                }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

                cache = new InsightCache(redis, Duration.ofMinutes(15));
        }

        @Test
        void roundTripsEntryAsJsonWithTtl() {
                cache.write(KEY, fixture());

                CachedInsight read = cache.read(KEY);

                assertThat(read).isEqualTo(fixture());
                assertThat(store.get(KEY))
                                .contains("\"provider\":\"gemini\"")
                                .contains("\"model\":\"gemini-2.5-flash\"")
                                .contains("\"tips\":\"fixture tips\"");
                verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        void treatsUnreadableEntriesAsCacheMiss() {
                store.put(KEY, "{not-json");

                assertThat(cache.read(KEY)).isNull();
        }

        @Test
        void degradesToCacheMissWhenRedisIsUnreachable() {
                doThrow(new RedisConnectionFailureException("fixture connection failure"))
                                .when(valueOperations).get(anyString());

                assertThat(cache.read(KEY)).isNull();
        }

        @Test
        void writeFailureDoesNotThrow() {
                doThrow(new RedisConnectionFailureException("fixture connection failure"))
                                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

                assertThatCode(() -> cache.write(KEY, fixture())).doesNotThrowAnyException();
        }

        @Test
        void nullWriteIsNoOp() {
                assertThatCode(() -> cache.write(KEY, null)).doesNotThrowAnyException();
                assertThat(store).isEmpty();
        }

        private static CachedInsight fixture() {
                return new CachedInsight("fixture tips", 12.5, "gemini", "gemini-2.5-flash",
                                10, 5, 42);
        }
}
