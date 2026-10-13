package com.energytracker.userservice.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.energytracker.userservice.dto.UserDto;
import com.energytracker.userservice.service.UserService;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@AllArgsConstructor
public class UserController {

        private final UserService userService;

        @GetMapping("/{userId}")
        public ResponseEntity<UserDto> getUserProfile(@PathVariable Long userId) {

                UserDto userDto = userService.getUserById(userId);
                if (userDto == null) {
                        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }
                return ResponseEntity.ok(userDto);
        }

        @GetMapping("/demo")
        public ResponseEntity<UserDto> getDemoUser() {
                UserDto demoUser = userService.getDemoUser();
                if (demoUser == null) {
                        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }
                return ResponseEntity.ok(demoUser);
        }

        @GetMapping("/email/{email}")
        public ResponseEntity<UserDto> getUserByEmail(@PathVariable String email) {
                UserDto userDto = userService.getUserByEmail(email);
                if (userDto == null) {
                        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }
                return ResponseEntity.ok(userDto);
        }

        @PostMapping("/register")
        public ResponseEntity<UserDto> createUser(@Valid @RequestBody UserDto UserDto) {
                UserDto createdUser = userService.createUser(UserDto);
                return new ResponseEntity<>(createdUser, HttpStatus.CREATED);
        }

        @PutMapping("/{userId}")
        public ResponseEntity<UserDto> updateUser(@PathVariable Long userId, @RequestBody UserDto userDto) {
                try {
                        UserDto updatedUser = userService.updateUser(userId, userDto);
                        if (updatedUser == null) {
                                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                        }
                        return ResponseEntity.ok(updatedUser);
                } catch (ResponseStatusException e) {
                        throw e;
                } catch (Exception e) {
                        log.error("Failed to update user {}", userId, e);
                        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                }
        }

        @DeleteMapping("/{userId}")
        public ResponseEntity<Void> deleteUser(@PathVariable Long userId) {
                try {
                        userService.deleteUser(userId);
                        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
                } catch (ResponseStatusException e) {
                        throw e;
                } catch (DataIntegrityViolationException e) {
                        // fk_device_user (ON DELETE RESTRICT): the user still owns devices
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "User still owns devices; delete them first");
                } catch (Exception e) {
                        log.error("Failed to delete user {}", userId, e);
                        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                }
        }
}
