package com.energytracker.alertservice.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "alert")
public class Alert {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "alert_id")
        private Long id;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "message")
        private String message;

        @Column(name = "threshold")
        private Double threshold;

        @Column(name = "energy_consumed")
        private Double energyConsumed;

        @Column(name = "email")
        private String email;

        @Builder.Default
        @Column(name = "sent", nullable = false)
        private Boolean sent = false;

        @Column(name = "created_at", insertable = false, updatable = false)
        private LocalDateTime createdAt;

        @Column(name = "updated_at", insertable = false, updatable = false)
        private LocalDateTime updatedAt;
}
