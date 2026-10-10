package com.energytracker.deviceservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.energytracker.deviceservice.entity.Device;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

}
