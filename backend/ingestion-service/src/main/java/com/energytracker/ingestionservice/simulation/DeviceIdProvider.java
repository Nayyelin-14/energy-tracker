package com.energytracker.ingestionservice.simulation;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.energytracker.ingestionservice.client.DeviceClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Supplies real device ids (created via device-service) to the simulations.
 *
 * <p>Call {@link #refresh()} once per simulation tick; {@link #nextId()} then
 * picks randomly from the cached list without touching the network, so a
 * device-service outage degrades to the last known ids instead of breaking
 * the tick.
 */
@Component
@Slf4j
public class DeviceIdProvider {

        private final DeviceClient deviceClient;
        private volatile List<Long> knownDeviceIds = List.of();

        public DeviceIdProvider(DeviceClient deviceClient) {
                this.deviceClient = deviceClient;
        }

        /**
         * Refreshes the cached id list from device-service. On failure the
         * previous list is kept so simulations can continue.
         */
        public void refresh() {
                try {
                        List<Long> ids = deviceClient.getAllDeviceIds();
                        knownDeviceIds = ids != null ? List.copyOf(ids) : List.of();
                } catch (Exception e) {
                        log.warn("Failed to fetch device ids from device-service, keeping {} cached id(s): {}",
                                        knownDeviceIds.size(), e.getMessage());
                }
        }

        /**
         * A random id from the last refreshed list, or empty when no devices
         * are known yet.
         */
        public OptionalLong nextId() {
                List<Long> ids = knownDeviceIds;
                if (ids.isEmpty()) {
                        return OptionalLong.empty();
                }
                return OptionalLong.of(ids.get(ThreadLocalRandom.current().nextInt(ids.size())));
        }
}
