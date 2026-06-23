package com.dentapinos.dataguard.exception;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Builder
public class ApiErrorResponse {
    private final LocalDateTime timestamp = LocalDateTime.now();
    @Setter
    private String message;
    @Setter
    private String details;

    public ApiErrorResponse(String message, String details) {
        this.message = message;
        this.details = details;
    }
}
