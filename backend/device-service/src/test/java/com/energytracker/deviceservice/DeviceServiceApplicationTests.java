package com.energytracker.deviceservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.energytracker.deviceservice.entity.Device;
import com.energytracker.deviceservice.model.DeviceType;
import com.energytracker.deviceservice.repository.DeviceRepository;

@SpringBootTest
class DeviceServiceApplicationTests {

	public static final int NUMBER_OF_DEVICES = 200;

	@Autowired
	DeviceRepository deviceRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void createDevices() {
		List<Long> userIds = jdbcTemplate.queryForList("""
				SELECT user_id
				FROM users
				WHERE email LIKE 'device.user%@energytracker.dev'
				ORDER BY email
				""", Long.class);

		assertThat(userIds)
				.as("No seeded users found - run the userservice `createUsers` test first")
				.isNotEmpty();

		deviceRepository.deleteAll();

		for (int i = 1; i <= NUMBER_OF_DEVICES; i++) {
			var device = Device.builder()
					.name("Device" + i)
					.type(DeviceType.values()[i % DeviceType.values().length])
					.location("Location" + ((i % 3) + 1))
					.userId(userIds.get((i - 1) % userIds.size()))
					.build();
			deviceRepository.save(device);
		}

		assertThat(deviceRepository.count()).isEqualTo(NUMBER_OF_DEVICES);
	}

}
