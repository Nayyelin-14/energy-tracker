package com.energytracker.insightservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.energytracker.insightservice.config.InsightProperties;
import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.dto.DeviceDto;
import com.energytracker.insightservice.dto.InsightType;
import com.energytracker.insightservice.dto.UsageDto;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns raw usage data into the LLM prompt and the matching cache key.
 * Stateless apart from the configured device cap.
 */
@Component
@Slf4j
public class InsightPromptAssembler {

        static final String SYSTEM_PROMPT = """
                        You are an expert household energy-efficiency advisor.
                        Answer only from the data provided in the user message.
                        Respond with at most 5 concise bullet points (max 20 words each).
                        All quantities are in kWh. Never invent statistics or comparisons.""";

        private final int maxDevices;

        public InsightPromptAssembler(InsightProperties properties) {
                this.maxDevices = properties.prompt() != null && properties.prompt().maxDevices() != null
                                ? properties.prompt().maxDevices()
                                : 10;
        }

        public List<DeviceDto> prepareDevices(UsageDto usageData) {
                if (usageData.devices() == null) {
                        return List.of();
                }
                List<DeviceDto> devices = usageData.devices().stream()
                                .filter(Objects::nonNull)
                                .filter(device -> device.energyConsumed() > 0)
                                .sorted(Comparator.comparingDouble(DeviceDto::energyConsumed).reversed())
                                .toList();
                if (devices.size() > maxDevices) {
                        log.info("Truncating device list from {} to top {} by consumption",
                                        devices.size(), maxDevices);
                        devices = devices.stream().limit(maxDevices).toList();
                }
                return devices;
        }

        public String buildUserPrompt(InsightType type, List<DeviceDto> devices, double totalUsage,
                        int effectiveDays) {
                String deviceTable = devices.stream()
                                .map(device -> "%s | %s | %s | %.2f | %.1f%%".formatted(
                                                device.name(),
                                                device.type(),
                                                device.location(),
                                                device.energyConsumed(),
                                                totalUsage > 0
                                                                ? 100.0 * device.energyConsumed() / totalUsage
                                                                : 0.0))
                                .collect(Collectors.joining("\n"));

                return """
                                Task: %s
                                Reporting period: past %d days
                                Total consumption: %.2f kWh

                                Device usage (name | type | location | kWh | share):
                                %s
                                """.formatted(type.promptInstruction(), effectiveDays, totalUsage, deviceTable);
        }

        public String cacheKey(ResolvedProvider provider, int effectiveDays, InsightType type,
                        Long userId, List<DeviceDto> devices) {
                return provider.id() + ":" + provider.modelId() + ":" + effectiveDays + ":" + type
                                + ":" + userId + ":" + fingerprint(devices);
        }

        private String fingerprint(List<DeviceDto> devices) {
                String canonical = devices.stream()
                                .map(device -> device.deviceId() + ":" + device.name() + ":"
                                                + device.type() + ":" + device.location() + ":"
                                                + String.format("%.2f", device.energyConsumed()))
                                .collect(Collectors.joining("|"));
                try {
                        byte[] hash = MessageDigest.getInstance("SHA-256")
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8));
                        return HexFormat.of().formatHex(hash).substring(0, 16);
                } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException("SHA-256 not available", e);
                }
        }
}
