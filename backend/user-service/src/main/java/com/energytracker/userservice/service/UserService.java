package com.energytracker.userservice.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.energytracker.userservice.dto.UserDto;
import com.energytracker.userservice.model.User;
import com.energytracker.userservice.repository.UserRepository;
import com.energytracker.userservice.util.UniqueIdGenerator;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@AllArgsConstructor
public class UserService {

    /**
     * Bounded retry when a generated user id collides with an existing row
     * (astronomically unlikely with 63 random bits, but never let it silently
     * fail a registration).
     */
    private static final int MAX_INSERT_ATTEMPTS = 3;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserDto createUser(UserDto input) {
        if (!input.getPassword().equals(input.getConfirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password and Confirm Password do not match");
        }

        if (userRepository.existsByEmail(input.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered: " + input.getEmail());
        }


        final User user = User.builder()
                    .name(input.getName())
                    .email(input.getEmail())
                    .address(input.getAddress())
                    .alerting(Boolean.TRUE.equals(input.getAlerting()))
                    .energyAlertingThreshold(
                            input.getEnergyAlertingThreshold() != null ? input.getEnergyAlertingThreshold() : 0.0)
                    .demo(Boolean.TRUE.equals(input.getDemo()))
                    .password(passwordEncoder.encode(input.getPassword()))
                    .build();

        final User savedUser = saveNewUser(user);
        return toDto(savedUser);
    }

    public UserDto getUserById(Long userId) {
         return  userRepository.findById(userId)
            .map(this::toDto)
            .orElse(null);
    }

    public UserDto getDemoUser() {
        return userRepository.findFirstByDemoTrue()
            .map(this::toDto)
            .orElse(null);
    }

    public UserDto getUserByEmail(String email) {
        return userRepository.findByEmail(email)
            .map(this::toDto)
            .orElse(null);
    }

    public UserDto updateUser(Long userId, UserDto input) {
        User existing = userRepository.findById(userId).orElse(null);
        if (existing == null) {
            return null;
        }

        if (input.getEmail() != null && !input.getEmail().equals(existing.getEmail())
                && userRepository.existsByEmail(input.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered: " + input.getEmail());
        }

        // Partial update: only overwrite fields that were actually provided, so a
        // sparse PUT body can no longer null out required columns or reset
        // alerting/threshold to their Java defaults.
        if (input.getEmail() != null) {
            existing.setEmail(input.getEmail());
        }
        if (input.getName() != null) {
            existing.setName(input.getName());
        }
        if (input.getAddress() != null) {
            existing.setAddress(input.getAddress());
        }
        if (input.getAlerting() != null) {
            existing.setAlerting(input.getAlerting());
        }
        if (input.getEnergyAlertingThreshold() != null) {
            final double threshold = input.getEnergyAlertingThreshold();
            if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "energyAlertingThreshold must be a non-negative number");
            }
            existing.setEnergyAlertingThreshold(threshold);
        }
        if (input.getDemo() != null) {
            existing.setDemo(input.getDemo());
        }

        return toDto(userRepository.save(existing));
    }

    public void deleteUser(Long userId) {
      User user = userRepository.findById(userId)
                    .orElseThrow(()-> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        userRepository.delete(user);
    }

    private User saveNewUser(User user) {
        for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
            // With an app-assigned id, JPA save() uses merge(), which would silently
            // OVERWRITE an existing row on an id collision instead of failing - so
            // guard against the known id first.
            if (userRepository.existsById(user.getUserId())) {
                if (attempt >= MAX_INSERT_ATTEMPTS) {
                    throw new DataIntegrityViolationException(
                            "Generated user id still collides after " + MAX_INSERT_ATTEMPTS + " attempts");
                }
                log.warn("Duplicate user id {} generated, retrying (attempt {}/{})",
                        user.getUserId(), attempt, MAX_INSERT_ATTEMPTS);
                user.setUserId(UniqueIdGenerator.nextId());
                continue;
            }
            try {
                // saveAndFlush surfaces constraint violations (unique email, duplicate
                // key) inside this call instead of at transaction commit
                return userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                if (attempt >= MAX_INSERT_ATTEMPTS || !isDuplicateId(e, user.getUserId())) {
                    // Not an id collision (e.g. email unique, FK violation) or retries
                    // exhausted: surface the original error instead of swallowing it.
                    throw e;
                }
                log.warn("Duplicate user id {} generated, retrying (attempt {}/{})",
                        user.getUserId(), attempt, MAX_INSERT_ATTEMPTS);
                user.setUserId(UniqueIdGenerator.nextId());
            }
        }
        throw new IllegalStateException("unreachable: saveNewUser exhausted its retry loop");
    }

    /**
     * Matches MySQL's duplicate-key message for this exact id value so that
     * unrelated constraint violations (unique email, FK on device.user_id)
     * are rethrown untouched.
     */
    private static boolean isDuplicateId(Throwable ex, Long id) {
        if (id == null) {
            return false;
        }
        final String marker = "Duplicate entry '" + id + "'";
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            final String message = cause.getMessage();
            if (message != null && message.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .address(user.getAddress())
                .alerting(user.isAlerting())
                .energyAlertingThreshold(user.getEnergyAlertingThreshold())
                .demo(user.isDemo())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
