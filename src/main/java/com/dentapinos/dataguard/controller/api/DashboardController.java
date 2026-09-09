package com.dentapinos.dataguard.controller.api;

import com.dentapinos.dataguard.config.DashboardAuthProperties;
import com.dentapinos.dataguard.entity.dashboard.DashboardResponseDto;
import com.dentapinos.dataguard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * REST Controller для dashboard мониторинга бэкапов.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "API для мониторинга резервных копий")
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardAuthProperties authProperties;

    /**
     * Получает данные для dashboard.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Получить данные dashboard", description = "Возвращает информацию о бэкапах (обезличенные если включён Safe Mode)")
    public ResponseEntity<DashboardResponseDto> getDashboard(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"DataGuard Dashboard\"")
                    .body(null);
        }

        // Используем Safe Mode если включён в конфиге
        DashboardResponseDto data = authProperties.isAlwaysSafeMode() 
                ? dashboardService.getSafeDashboardData()
                : dashboardService.getDashboardData();

        return ResponseEntity.ok(data);
    }

    /**
     * Проверяет авторизацию пользователя.
     */
    private boolean isAuthorized(@Nullable String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }

        try {
            String encodedCredentials = authorization.substring(6);
            String decodedCredentials = new String(Base64.getDecoder().decode(encodedCredentials));
            String[] credentials = decodedCredentials.split(":", 2);

            if (credentials.length != 2) {
                return false;
            }

            String username = credentials[0];
            String password = credentials[1];

            return authProperties.getUsername().equals(username) 
                    && authProperties.getPassword().equals(password);
        } catch (Exception e) {
            return false;
        }
    }
}