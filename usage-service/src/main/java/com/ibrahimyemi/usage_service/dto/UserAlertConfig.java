package com.ibrahimyemi.usage_service.dto;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class UserAlertConfig {

    private Long userId;

    private Double threshold;

    private String email;
}
