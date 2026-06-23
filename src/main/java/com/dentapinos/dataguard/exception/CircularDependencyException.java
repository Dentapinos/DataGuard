package com.dentapinos.dataguard.exception;

import lombok.Getter;

/**
 * Исключение для ошибок циклических зависимостей.
 */
@Getter
public class CircularDependencyException extends RuntimeException {
    public CircularDependencyException(String message, String cycleInfo) {
        super(message);
        this.cycleInfo = cycleInfo;
    }

    private final String cycleInfo;

}
