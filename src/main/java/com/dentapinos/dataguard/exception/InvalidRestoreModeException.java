package com.dentapinos.dataguard.exception;

/**
 * Исключение, выбрасываемое при ошибке выбора режима восстановления.
 * <p>
 * Клиентская ошибка. Указывает на некорректный или неизвестный режим восстановления.
 */
public class InvalidRestoreModeException extends RuntimeException {

    public InvalidRestoreModeException(String message) {
        super(message);
    }

    public InvalidRestoreModeException(String message, Throwable cause) {
        super(message, cause);
    }
}
