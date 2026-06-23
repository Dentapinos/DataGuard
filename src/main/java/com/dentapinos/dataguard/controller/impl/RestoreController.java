package com.dentapinos.dataguard.controller.impl;

import com.dentapinos.dataguard.controller.api.RestoreApi;
import com.dentapinos.dataguard.dto.*;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.ApiErrorResponse;
import com.dentapinos.dataguard.exception.DatabaseCreationException;
import com.dentapinos.dataguard.exception.DatabaseNotFoundException;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.service.DatabaseSchemaCreator;
import com.dentapinos.dataguard.service.restore.RestoreService;
import com.dentapinos.dataguard.service.restore.SchemaCompatibilityService;
import com.dentapinos.dataguard.storage.BackupFileNamingService;
import com.dentapinos.dataguard.storage.BackupFileReader;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequiredArgsConstructor
@Slf4j
public class RestoreController implements RestoreApi {

    private final RestoreService restoreService;
    private final BackupStorage backupStorage;
    private final BackupFileNamingService fileNameParser;
    private final BackupFileReader backupFileReader;
    private final DatabaseConfigResolver databaseConfigResolver;
    private final DatabaseSchemaCreator databaseSchemaCreator;
    private final SchemaCompatibilityService schemaCompatibilityService;

    /**
     * Запуск восстановления в существующую базу.
     */
    @Override
    public ResponseEntity<?> restoreToExistingDatabase(
            BackupTier tier,
            @RequestBody RestoreRequest request
    ) {
        DbCredentials credentials;
        try {
            credentials = databaseConfigResolver.resolveCredentials(request.targetDatabase());
        } catch (DatabaseNotFoundException ex) {
            log.warn("[RESTORE] База данных не найдена: {}", request.targetDatabase(), ex);

            ApiErrorResponse error = new ApiErrorResponse();
            error.setMessage("База данных не найдена");
            error.setDetails("База данных '" + request.targetDatabase() + "' отсутствует в конфигурации");

            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        log.info("[RESTORE] Запуск восстановления: logicalDbName={}, targetDatabase={}, backupName={}, mode={}, tables={}",
                request.targetDatabase(), request.targetDatabase(), request.backupName(), request.mode(),
                request.tables() == null ? "all" : request.tables());

        // Всё остальное — пусть кидает RestoreOperationException/IOException
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                tier,
                request.backupName(),
                request.targetDatabase(),
                request.mode(),
                request.tables()
        );
        return ResponseEntity.ok(report);
    }

    /**
     * Предварительный анализ совместимости схемы для восстановления.
     * Ничего в целевой БД не меняет.
     */
    @Override
    public ResponseEntity<?> analyzeSchemaCompatibility(
            String databaseName,
            BackupTier tier,
            AnalyzeSchemaRequest request
    ) {

        DbCredentials credentials;
        try {
            credentials = databaseConfigResolver.resolveCredentials(databaseName);
            if (!databaseName.equals(request.targetDatabase())) {
                //проверка доступа к целевой бд
                databaseConfigResolver.resolveCredentials(request.targetDatabase());
            }
        } catch (IllegalArgumentException ex) {
            log.warn("[AD_HOC_RESTORE_ANALYZE] {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
        }

        log.info("[AD_HOC_RESTORE_ANALYZE] Анализ совместимости схемы: logicalDbName={}, targetDatabase={}, backupName={}",
                databaseName, request.targetDatabase(), request.backupName());

        try {
            BackupFile backup = loadBackup(databaseName, request.backupName(), tier);

            SchemaCompatibilityAnalysisDto analysis = schemaCompatibilityService.analyzeCompatibility(
                    credentials,
                    backup,
                    request.targetDatabase()
            );

            return ResponseEntity.ok(analysis);
        } catch (IOException e) {
            log.error("[AD_HOC_RESTORE_ANALYZE]Ошибка ввода-вывода при анализе: logicalDbName={}, targetDatabase={}, backupName={}",
                    databaseName, request.targetDatabase(), request.backupName(), e);
            throw new RuntimeException("Ошибка ввода-вывода при анализе файла " + request.backupName());
        } catch (Exception e) {
            log.error("[AD_HOC_RESTORE_ANALYZE] Неожиданная ошибка при анализе: logicalDbName={}, targetDatabase={}, backupName={}",
                    databaseName, request.targetDatabase(), request.backupName(), e);
            throw e;
        }
    }

    @PostMapping(value = "/new-database", consumes = "application/json", produces = "application/json")
    @Override
    public ResponseEntity<?> restoreToNewDatabase(
            @RequestParam(required = false) BackupTier tier,
            @RequestBody RestoreToNewDatabaseRequest request
    ) {

        String parseDatabaseName = fileNameParser.parseDatabaseName(request.backupName());

        try {
            // 1. Загрузка бэкапа
            BackupFile backup = loadBackup(parseDatabaseName, request.backupName(), tier);

            // 2. Создание новой БД
            databaseSchemaCreator.createDatabaseIfNotExistsElseException(
                    request.newDatabaseCredentials(),
                    request.newDatabaseName()
            );

            // 3. Создание таблиц
            databaseSchemaCreator.createTables(
                    request.newDatabaseCredentials(),
                    request.newDatabaseName(),
                    backup.schema()
            );

            // 4. Запуск восстановления данных в новую БД
            DbCredentials newDbCredentials = request.newDatabaseCredentials();
            RestoreReport report = restoreService.restoreToExistingDatabase(
                    newDbCredentials,
                    tier,
                    request.backupName(),
                    request.newDatabaseName(),
                    com.dentapinos.dataguard.enums.RestoreMode.STRICT,
                    null // таблицы не фильтруются - восстанавливаются все из бэкапа
            );
            return ResponseEntity.ok(report);

        } catch (DatabaseCreationException e){
            log.error("[AD_HOC_RESTORE_NEW_DB] Ошибка восстановления в новую базу данных: newDb={}, backupName={}",
                    request.newDatabaseName(), request.backupName(), e);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    e.getMessage(),
                    "Ошибка при попытке восстановить данные в новую базу данных ["+request.newDatabaseName()+"], которая уже существует"
            ));
        } catch (Exception e) {
            log.error("[AD_HOC_RESTORE_NEW_DB] Ошибка восстановления в новую базу данных: newDb={}, backupName={}",
                    request.newDatabaseName(), request.backupName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Неожиданная ошибка при восстановлении в новую базу данных: " + e.getMessage());
        }
    }

    private BackupFile loadBackup(String databaseName, String backupName, BackupTier tier) throws IOException {
        try (InputStream is = backupStorage.load(tier, databaseName, backupName)) {
            return backupFileReader.readBackupFile(is);
        }
    }
}
