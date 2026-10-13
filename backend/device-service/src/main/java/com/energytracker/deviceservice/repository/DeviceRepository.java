package com.energytracker.deviceservice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.energytracker.deviceservice.entity.Device;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

        List<Device> findAllByUserId(Long userId);
}
