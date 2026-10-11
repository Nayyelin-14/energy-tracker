package com.energytracker.userservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.energytracker.userservice.model.User;
import com.energytracker.userservice.repository.UserRepository;

@SpringBootTest
class UserserviceApplicationTests {

	public static final int NUMBER_OF_USERS = 10;
	public static final String SEED_EMAIL_PREFIX = "device.user";

	@Autowired
	UserRepository userRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void createUsers() {
		for (int i = 1; i <= NUMBER_OF_USERS; i++) {
			String email = SEED_EMAIL_PREFIX + i + "@energytracker.dev";

			// upsert-by-email: never delete, so the generated user_id stays stable forever
			if (userRepository.existsByEmail(email)) {
				continue;
			}

			userRepository.save(User.builder()
					.email(email)
					.password("Password123!")
					.name("Device User " + i)
					.address("Yangon")
					.alerting(true)
					.energyAlertingThreshold(500.0 + (i * 50))
					.build());
		}

		assertThat(seededUserCount()).isEqualTo(NUMBER_OF_USERS);
	}

	@Test
	void everyDeviceReferencesAnExistingUser() {
		Integer orphans = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM device d
				LEFT JOIN users u ON u.user_id = d.user_id
				WHERE u.user_id IS NULL
				""", Integer.class);

		assertThat(orphans)
				.as("device.user_id values that do not exist in users.user_id (run createUsers first, then the device-service createDevices test)")
				.isZero();
	}

	private long seededUserCount() {
		return userRepository.findAll().stream()
				.filter(user -> user.getEmail().startsWith(SEED_EMAIL_PREFIX))
				.count();
	}

}
