package com.dentapinos.dataguard.exception;


/**
 * Исключение, выбрасываемое, когда запрошенная база данных не найдена в конфигурации.
 */
public class DatabaseNotFoundException extends RuntimeException {

    public DatabaseNotFoundException(String message) {
        super(message);
    }

    public DatabaseNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

