package com.energytracker.userservice.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private Long userId;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private  String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be six character")
    private String password;

    @NotBlank(message = "Confirm Password is required")
    @Size(min = 6, message = "Confirm Password must be six character")
    private String confirmPassword;

    private String name;

    private String address;
    private Boolean alerting;

    private Double energyAlertingThreshold;


    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
