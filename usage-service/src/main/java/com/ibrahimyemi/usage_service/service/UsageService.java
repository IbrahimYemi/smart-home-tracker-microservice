package com.ibrahimyemi.usage_service.service;

import com.ibrahimyemi.kafka.event.AlertingEvent;
import com.ibrahimyemi.usage_service.client.DeviceClient;
import com.ibrahimyemi.usage_service.client.UserClient;
import com.ibrahimyemi.usage_service.dto.DeviceDto;
import com.ibrahimyemi.usage_service.dto.UserAlertConfig;
import com.ibrahimyemi.usage_service.dto.UserDto;
import com.ibrahimyemi.usage_service.model.DeviceEnergy;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.ibrahimyemi.kafka.event.EnergyUsageEvent;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UsageService {

    @Autowired
    private InfluxDBClient influxDBClient;

    @Autowired
    private DeviceClient deviceClient;

    @Autowired
    private UserClient userClient;

    @Value("${influx.bucket}")
    private String influxBucket;

    @Value("${influx.org}")
    private String influxOrg;

    @Autowired
    private final KafkaTemplate<String, AlertingEvent> kafkaTemplate;

    public UsageService(KafkaTemplate<String, AlertingEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "energy-usage", groupId = "usage-service")
    public void energyUsageEvent(EnergyUsageEvent energyUsageEvent) {
        Point point = Point.measurement("energy_usage")
                .addTag("deviceId", String.valueOf(energyUsageEvent.deviceId()))
                .addField("energyConsumed", energyUsageEvent.energyConsumed())
                .time(energyUsageEvent.timestamp(), WritePrecision.MS);
        influxDBClient.getWriteApiBlocking().writePoint(influxBucket, influxOrg, point);
    }

    @Scheduled(cron = "*/10 * * * * *")
    public void aggregateDeviceEnergyUsage() {

        final Instant now = Instant.now();
        final Instant oneHourAgo = now.minusSeconds(3600);

        final List<FluxTable> tables = getFluxTables(oneHourAgo, now);

        final List<DeviceEnergy> deviceEnergies = extractDeviceEnergies(tables);

        attachUsersToDevices(deviceEnergies);

        deviceEnergies.removeIf(deviceEnergy -> deviceEnergy.getUserId() == null);

        final Map<Long, List<DeviceEnergy>> userDeviceEnergyMap =
                groupDeviceEnergyByUser(deviceEnergies);

        final Map<Long, UserAlertConfig> userAlertConfigMap =
                fetchAlertingUsers(userDeviceEnergyMap.keySet());

        processEnergyAlerts(userDeviceEnergyMap, userAlertConfigMap);
    }

    private @NonNull List<FluxTable> getFluxTables(
            Instant oneHourAgo,
            Instant now
    ) {

        String fluxQuery = String.format("""
        from(bucket: "%s")
          |> range(start: time(v: "%s"), stop: time(v: "%s"))
          |> filter(fn: (r) => r["_measurement"] == "energy_usage")
          |> filter(fn: (r) => r["_field"] == "energyConsumed")
          |> group(columns: ["deviceId"])
          |> sum(column: "_value")
        """,
                influxBucket,
                oneHourAgo,
                now
        );

        QueryApi queryApi = influxDBClient.getQueryApi();

        return queryApi.query(fluxQuery, influxOrg);
    }

    private List<DeviceEnergy> extractDeviceEnergies(List<FluxTable> tables) {

        final List<DeviceEnergy> deviceEnergies = new ArrayList<>();

        for (FluxTable table : tables) {

            for (FluxRecord record : table.getRecords()) {

                String deviceIdStr =
                        (String) record.getValueByKey("deviceId");

                double energyConsumed =
                        record.getValueByKey("_value") instanceof Number
                                ? ((Number) Objects.requireNonNull(
                                record.getValueByKey("_value")
                        )).doubleValue()
                                : 0.0;

                if (deviceIdStr == null) {
                    continue;
                }

                deviceEnergies.add(
                        DeviceEnergy.builder()
                                .deviceId(Long.valueOf(deviceIdStr))
                                .energyConsumed(energyConsumed)
                                .build()
                );
            }
        }

        log.info(
                "Extracted {} device energy records",
                deviceEnergies.size()
        );

        return deviceEnergies;
    }

    private void attachUsersToDevices(List<DeviceEnergy> deviceEnergies) {

        for (DeviceEnergy deviceEnergy : deviceEnergies) {

            try {

                final DeviceDto device =
                        deviceClient.getDeviceById(
                                deviceEnergy.getDeviceId()
                        );

                if (device == null || device.id() == null) {

                    log.warn(
                            "Device not found for ID: {}",
                            deviceEnergy.getDeviceId()
                    );

                    continue;
                }

                deviceEnergy.setUserId(device.userId());

            } catch (Exception e) {

                log.warn(
                        "Failed to fetch device for ID: {}",
                        deviceEnergy.getDeviceId()
                );
            }
        }
    }

    private Map<Long, List<DeviceEnergy>> groupDeviceEnergyByUser(
            List<DeviceEnergy> deviceEnergies
    ) {

        final Map<Long, List<DeviceEnergy>> userDeviceEnergyMap =
                deviceEnergies.stream()
                        .collect(Collectors.groupingBy(
                                DeviceEnergy::getUserId
                        ));

        log.info(
                "Grouped device energies for {} users",
                userDeviceEnergyMap.size()
        );

        return userDeviceEnergyMap;
    }

    private Map<Long, UserAlertConfig> fetchAlertingUsers(
            Set<Long> userIds
    ) {

        final Map<Long, UserAlertConfig> userAlertConfigMap =
                new HashMap<>();

        for (Long userId : userIds) {

            try {

                UserDto user = userClient.getUserById(userId);

                if (user == null ||
                        user.id() == null ||
                        !user.alerting()) {

                    log.warn(
                            "User not found or alerting disabled for ID: {}",
                            userId
                    );

                    continue;
                }

                userAlertConfigMap.put(
                        userId,
                        UserAlertConfig.builder()
                                .userId(user.id())
                                .threshold(
                                        user.energyAlertingThreshold()
                                )
                                .email(user.email())
                                .build()
                );

            } catch (Exception e) {

                log.warn(
                        "Failed to fetch user for ID: {}",
                        userId
                );
            }
        }

        log.info(
                "Loaded alert config for {} users",
                userAlertConfigMap.size()
        );

        return userAlertConfigMap;
    }

    private void processEnergyAlerts(
            Map<Long, List<DeviceEnergy>> userDeviceEnergyMap,
            Map<Long, UserAlertConfig> userAlertConfigMap
    ) {

        for (Map.Entry<Long, UserAlertConfig> entry :
                userAlertConfigMap.entrySet()) {

            final Long userId = entry.getKey();

            final UserAlertConfig config = entry.getValue();

            final List<DeviceEnergy> devices =
                    userDeviceEnergyMap.get(userId);

            final double totalConsumption =
                    devices.stream()
                            .mapToDouble(
                                    DeviceEnergy::getEnergyConsumed
                            )
                            .sum();

            if (totalConsumption > config.getThreshold()) {

                log.info(
                        "ALERT: User ID {} exceeded threshold. " +
                                "Consumption={}, Threshold={}",
                        userId,
                        totalConsumption,
                        config.getThreshold()
                );

                final AlertingEvent alertingEvent =
                        AlertingEvent.builder()
                                .userId(userId)
                                .message(
                                        "Energy consumption threshold exceeded"
                                )
                                .threshold(config.getThreshold())
                                .energyConsumed(totalConsumption)
                                .email(config.getEmail())
                                .build();

                kafkaTemplate.send(
                        "energy-alerts",
                        alertingEvent
                );

            } else {

                log.info(
                        "User ID {} within threshold. " +
                                "Consumption={}, Threshold={}",
                        userId,
                        totalConsumption,
                        config.getThreshold()
                );
            }
        }
    }

}
