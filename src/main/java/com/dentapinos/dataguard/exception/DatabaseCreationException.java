package com.dentapinos.dataguard.exception;

/**
 * Ошибки связанные с созданием новой базы
 */
public class DatabaseCreationException extends RuntimeException {

    public DatabaseCreationException(String message) {
        super(message);
    }

    public DatabaseCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
