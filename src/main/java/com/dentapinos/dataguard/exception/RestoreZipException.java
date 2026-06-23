package com.dentapinos.dataguard.exception;

/**
 * Исключение, выбрасываемое когда ZIP-архив поврежден или не найден.
 */
public class RestoreZipException extends RuntimeException {
    public RestoreZipException() {
    }

    public RestoreZipException(String message) {
        super(message);
    }

    public RestoreZipException(String message, Throwable cause) {
        super(message, cause);
    }

    public RestoreZipException(Throwable cause) {
        super(cause);
    }

    public RestoreZipException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
