package com.energytracker.deviceservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.energytracker.deviceservice.model.DeviceType;
import com.energytracker.deviceservice.util.UniqueIdGenerator;

@Entity
@Table(name = "device", indexes = {
                @Index(name = "idx_device_user_id", columnList = "user_id")
})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class Device {

        @Id
        @Column(name = "device_id", nullable = false)
        @Builder.Default
        private Long deviceId = UniqueIdGenerator.nextId();

        @Column(name = "name", nullable = false)
        private String name;

        @Enumerated(EnumType.STRING)
        @Column(name = "type", nullable = false)
        private DeviceType type;

        @Column(name = "location")
        private String location;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @CreationTimestamp
        @Column(name = "created_at", nullable = false)
        private LocalDateTime createdAt;

        @UpdateTimestamp
        @Column(name = "updated_at", nullable = false)
        private LocalDateTime updatedAt;
}
