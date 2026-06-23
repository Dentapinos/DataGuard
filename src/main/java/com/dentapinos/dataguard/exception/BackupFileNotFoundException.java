package com.dentapinos.dataguard.exception;

/**
 * Исключение, выбрасываемое когда резервная копия не найдена в хранилище.
 * <p>
 * Клиентская ошибка. Указывает, что запрошенная резервная копия отсутствует в хранилище.
 */
public class BackupFileNotFoundException extends BackupStorageException {

    public BackupFileNotFoundException(String message) {
        super(message);
    }

    public BackupFileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
