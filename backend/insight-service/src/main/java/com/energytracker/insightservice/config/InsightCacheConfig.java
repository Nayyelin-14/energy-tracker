package com.energytracker.insightservice.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class InsightCacheConfig {

        @Bean
        InsightCache insightCache(
                        StringRedisTemplate redis,
                        @Value("${insight.cache.ttl-minutes:15}") long ttlMinutes) {
                return new InsightCache(redis, Duration.ofMinutes(ttlMinutes));
        }
}
