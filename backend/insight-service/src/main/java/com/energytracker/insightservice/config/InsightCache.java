package com.energytracker.insightservice.config;

import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.energytracker.insightservice.service.InsightService.CachedInsight;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis-backed cache for generated insights (Upstash via {@code REDIS_URL}).
 *
 * <p>
 * Values are stored as JSON with a TTL. Redis outages and unreadable entries
 * degrade to a cache miss instead of failing the request; only keys, never
 * cached values, are logged. Writes happen explicitly via {@link #write} after
 * a generation fully succeeds, never mid-stream.
 */
@Slf4j
public class InsightCache {

        private static final JsonMapper MAPPER = JsonMapper.builder().build();

        private final StringRedisTemplate redis;
        private final Duration ttl;

        public InsightCache(StringRedisTemplate redis, Duration ttl) {
                this.redis = redis;
                this.ttl = ttl;
        }

        /** Returns the cached insight or null on miss / unreadable entry / Redis outage. */
        public CachedInsight read(String key) {
                String cached = readRaw(key);
                if (cached == null) {
                        return null;
                }
                try {
                        return MAPPER.readValue(cached, CachedInsight.class);
                } catch (JacksonException e) {
                        log.warn("Ignoring unreadable cache entry for key '{}'", key);
                        return null;
                }
        }

        public void write(String key, CachedInsight value) {
                if (value == null) {
                        return;
                }
                String json;
                try {
                        json = MAPPER.writeValueAsString(value);
                } catch (JacksonException e) {
                        log.warn("Could not serialize cache entry for key '{}'", key);
                        return;
                }
                try {
                        redis.opsForValue().set(key, json, ttl);
                } catch (DataAccessException e) {
                        log.warn("Redis cache write failed for key '{}' ({})", key,
                                        e.getClass().getSimpleName());
                }
        }

        private String readRaw(String key) {
                try {
                        return redis.opsForValue().get(key);
                } catch (DataAccessException e) {
                        log.warn("Redis cache read failed for key '{}' ({})", key,
                                        e.getClass().getSimpleName());
                        return null;
                }
        }
}
