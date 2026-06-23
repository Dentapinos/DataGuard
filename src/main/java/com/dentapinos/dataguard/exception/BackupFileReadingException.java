package com.dentapinos.dataguard.exception;

import java.io.IOException;

/**
 * Исключение, выбрасываемое при ошибках чтения резервной копии.
 * <p>
 * Указывает на проблемы при чтении содержимого файла бэкапа (формат, повреждение, права).
 */
public class BackupFileReadingException extends BackupStorageException {

    public BackupFileReadingException(String message) {
        super(message);
    }

    public BackupFileReadingException(String message, IOException cause) {
        super(message, cause);
    }
}
