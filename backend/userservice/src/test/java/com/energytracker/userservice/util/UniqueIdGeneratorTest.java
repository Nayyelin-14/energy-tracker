package com.energytracker.userservice.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.energytracker.userservice.model.User;

class UniqueIdGeneratorTest {

        @Test
        void nextIdIsAlwaysPositive() {
                for (int i = 0; i < 10_000; i++) {
                        assertThat(UniqueIdGenerator.nextId()).isPositive();
                }
        }

        @Test
        void nextIdsDoNotCollide() {
                Set<Long> ids = new HashSet<>();
                for (int i = 0; i < 100_000; i++) {
                        ids.add(UniqueIdGenerator.nextId());
                }
                assertThat(ids).hasSize(100_000);
        }

        @Test
        void userGetsDefaultIdFromNoArgsConstructor() {
                User user = new User();
                assertThat(user.getUserId()).isNotNull().isPositive();
        }

        @Test
        void userGetsDefaultIdFromBuilderWhenIdNotSet() {
                User user = User.builder().email("a@b.c").password("x").build();
                assertThat(user.getUserId()).isNotNull().isPositive();
        }
}
