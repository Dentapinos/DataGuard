package com.dentapinos.dataguard.exception;

import java.io.IOException;

/**
 * Исключение, выбрасываемое при ошибках чтения атрибутов файла резервной копии.
 * <p>
 * Указывает на проблемы при извлечении метаданных файла: размер, дата модификации, права доступа.
 */
public class BackupFileAttributesException extends BackupStorageException {

    public BackupFileAttributesException(String message) {
        super(message);
    }

    public BackupFileAttributesException(String message, IOException cause) {
        super(message, cause);
    }
}
