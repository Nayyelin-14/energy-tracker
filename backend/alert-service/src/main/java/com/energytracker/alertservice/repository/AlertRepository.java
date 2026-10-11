package com.energytracker.alertservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.energytracker.alertservice.entity.Alert;

public interface AlertRepository extends JpaRepository<Alert, Long> {

}
