package com.energytracker.userservice.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.energytracker.userservice.dto.UserDto;
import com.energytracker.userservice.model.User;
import com.energytracker.userservice.repository.UserRepository;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@AllArgsConstructor
public class UserService {

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
                    .alerting(input.isAlerting())
                    .energyAlertingThreshold(input.getEnergyAlertingThreshold())
                    .password(passwordEncoder.encode(input.getPassword()))
                    .build();

        final User savedUser = userRepository.save(user);
        return toDto(savedUser);
    }

    public UserDto getUserById(String userId) {
         return  userRepository.findById(userId)
            .map(this::toDto)
            .orElse(null);
    }

    public UserDto updateUser(String userId, UserDto input) {
        User existing = userRepository.findById(userId).orElse(null);
        if (existing == null) {
            return null;
        }

        if (input.getEmail() != null && !input.getEmail().equals(existing.getEmail())
                && userRepository.existsByEmail(input.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered: " + input.getEmail());
        }

        User user = User.builder()
                .userId(existing.getUserId())
                .password(existing.getPassword())
                .email(input.getEmail())
                .name(input.getName())
                .address(input.getAddress())
                .alerting(input.isAlerting())
                .energyAlertingThreshold(input.getEnergyAlertingThreshold())
                .createdAt(existing.getCreatedAt())
                .updatedAt(existing.getUpdatedAt())
                .build();

        return toDto(userRepository.save(user));
    }

    public void deleteUser(String userId) {
      User user = userRepository.findById(userId)
                    .orElseThrow(()-> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        userRepository.delete(user);
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .address(user.getAddress())
                .alerting(user.isAlerting())
                .energyAlertingThreshold(user.getEnergyAlertingThreshold())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
