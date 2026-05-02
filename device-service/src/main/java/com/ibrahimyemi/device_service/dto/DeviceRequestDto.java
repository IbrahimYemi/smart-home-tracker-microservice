package com.ibrahimyemi.device_service.dto;

import com.ibrahimyemi.device_service.enums.DeviceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class DeviceRequestDto {
    private String name;
    private DeviceType type;
    private String location;
    private Long userId;
}
