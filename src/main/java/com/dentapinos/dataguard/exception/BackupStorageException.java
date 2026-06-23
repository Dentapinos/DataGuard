package com.dentapinos.dataguard.exception;

/**
 * Базовое исключение для ошибок операций с резервными копиями.
 * <p>
 * Выбрасывается при любом сбое в работе с файлами резервных копий.
 */
public class BackupStorageException extends RuntimeException {

    public BackupStorageException(String message) {
        super(message);
    }

    public BackupStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
