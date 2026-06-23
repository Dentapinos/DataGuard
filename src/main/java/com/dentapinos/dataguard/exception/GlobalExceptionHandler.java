package com.dentapinos.dataguard.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(RestoreOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleRestoreOperationException(RestoreOperationException ex) {
        log.error("[RESTORE] RestoreOperationException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Ошибка при восстановлении данных");

        StringBuilder details = new StringBuilder();
        if (ex.getTable() != null) {
            details.append("Таблица: ").append(ex.getTable()).append(". ");
        }
        if (ex.getErrorCode() != 0) {
            details.append("Код SQL ошибки: ").append(ex.getErrorCode()).append(". ");
        }
        if (ex.getSqlMessage() != null) {
            details.append("Сообщение БД: ").append(ex.getSqlMessage());
        } else if (ex.getMessage() != null) {
            details.append(ex.getMessage());
        }
        error.setDetails(details.toString());

        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(BackupStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleBackupStorageException(BackupStorageException ex) {
        log.error("[RESTORE] BackupStorageException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Ошибка работы с бэкапами");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(BackupFileReadingException.class)
    public ResponseEntity<ApiErrorResponse> handleBackupFileReadingException(BackupFileReadingException ex) {
        log.error("[RESTORE] BackupFileReadingException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Ошибка чтения бэкапа");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(BackupFileNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleBackupFileNotFoundException(BackupFileNotFoundException ex) {
        log.error("[RESTORE] BackupFileNotFoundException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Бэкап не найден");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(BackupFileWritingException.class)
    public ResponseEntity<ApiErrorResponse> handleBackupFileWritingException(BackupFileWritingException ex) {
        log.error("[BACKUP] BackupFileWritingException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Ошибка записи бэкапа");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(BackupFileAttributesException.class)
    public ResponseEntity<ApiErrorResponse> handleBackupFileAttributesException(BackupFileAttributesException ex) {
        log.error("[BACKUP] BackupFileAttributesException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Ошибка чтения атрибутов бэкапа");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(InvalidRestoreModeException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRestoreModeException(InvalidRestoreModeException ex) {
        log.error("[RESTORE] InvalidRestoreModeException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Ошибка выбора режима восстановления");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(DatabaseNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabaseNotFoundException(DatabaseNotFoundException ex) {
        log.error("[RESTORE] DatabaseNotFoundException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("База данных не найдена");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(RestoreZipException.class)
    public ResponseEntity<ApiErrorResponse> handleRestoreZipException(RestoreZipException ex) {
        log.error("[RESTORE] RestoreZipException: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Поврежденный или не найденный ZIP-архив");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex) {
        log.error("[RESTORE] Необработанное исключение: {}", ex.getMessage(), ex);

        ApiErrorResponse error = new ApiErrorResponse();
        error.setMessage("Внутренняя ошибка сервера");
        error.setDetails(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}