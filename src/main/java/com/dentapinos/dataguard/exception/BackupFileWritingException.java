package com.dentapinos.dataguard.exception;

import java.io.IOException;

/**
 * Исключение, выбрасываемое при ошибках записи резервной копии.
 * <p>
 * Указывает на сбои при сохранении бэкапа в хранилище (диск, права, I/O).
 */
public class BackupFileWritingException extends BackupStorageException {

    public BackupFileWritingException(String message) {
        super(message);
    }

    public BackupFileWritingException(String message, IOException cause) {
        super(message, cause);
    }
}
