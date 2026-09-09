package com.dentapinos.dataguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Настройки аутентификации для dashboard.
 */
@Data
@ConfigurationProperties(prefix = "dashboard.auth")
@Validated
public class DashboardAuthProperties {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;

    /**
     * Всегда включать Safe Mode (обезличенные данные).
     * Если true — даже с правильным логином/паролем данные будут обезличены.
     */
    private boolean alwaysSafeMode = true;
}