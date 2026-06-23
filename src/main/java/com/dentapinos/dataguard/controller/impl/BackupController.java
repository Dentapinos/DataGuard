package com.dentapinos.dataguard.controller.impl;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.controller.api.BackupApi;
import com.dentapinos.dataguard.dto.*;
import com.dentapinos.dataguard.entity.storage.BackupFileInfo;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.DatabaseNotFoundException;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.service.BackupFacade;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BackupController implements BackupApi {

    private final BackupFacade backupFacade;
    private final BackupDatabasesProperties backupDatabasesProperties;
    private final DatabaseConfigResolver databaseConfigResolver;
    private final BackupStorage backupStorage;

    @Override
    public ResponseEntity<?> backupSelectedTables(String databaseName, BackupTablesRequest request) {

        final DbCredentials credentials;
        try {
            credentials = databaseConfigResolver.resolveCredentials(databaseName);
        } catch (IllegalArgumentException ex) {
            log.warn("[AD_HOC_BACKUP] {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
        }

        log.info("[AD_HOC_BACKUP] Запуск бэкапа выбранных таблиц: databaseName={}, tables={}",
                databaseName, request.tables());

        try {
            // 2. Делаем бэкап и сразу сохраняем через фасад
            BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, request.tables());
            String backupName = backupFacade.storeBackup(envelope, databaseName);

            BackupReport report = envelope.report();

            var response = new ExportResponse(
                    backupName,
                    databaseName,
                    BackupTier.DAILY,
                    report
            );

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("[AD_HOC_BACKUP] I/O error during backup: dbName={}, tables={}",
                    databaseName, request.tables(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("I/O error during backup: " + e.getMessage());
        }
    }

    @Override
    public ResponseEntity<?> listTables(String databaseName) {
        final DbCredentials credentials;
        try {
            credentials = databaseConfigResolver.resolveCredentials(databaseName);
        } catch (DatabaseNotFoundException ex) {
            log.warn("[TABLES_LOOKUP] {}", ex.getMessage());
            throw ex; //пробрасываем в глобальный обработчик
        }

        log.info("[TABLES_LOOKUP] Getting tables: databaseName={}", databaseName);

        try {
            // фасад сам обращается к нужному metadataReader
            List<String> tables = backupFacade.listTablesMySql(credentials, databaseName);
            return ResponseEntity.ok(new TablesResponse(tables));
        } catch (Exception e) {
            log.error("[TABLES_LOOKUP] Failed to load tables: databaseName={}", databaseName, e);
            throw e;
        }
    }

    @Override
    public ResponseEntity<List<DatabaseInfo>> listConfiguredDatabases() {
        var list = backupDatabasesProperties.getDatabases().stream()
                .map(db -> new DatabaseInfo(db.getDatabaseName(), db.getDisplayName()))
                .toList();
        return ResponseEntity.ok(list);
    }

    @Override
    public void downloadBackup(
            @RequestParam String database,
            @RequestParam String name,
            @RequestParam BackupTier tier,
            HttpServletResponse response
    ) {

        response.setContentType("application/zip");

        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + name + "\"");

        try {
            backupFacade.loadBackup(database, name, tier, response.getOutputStream());
        } catch (Exception e) {
            log.error("Ошибка файла");
            response.setHeader("error", "Ошибка при получении файла " + name);
        }
    }

    @Override
    public ResponseEntity<List<BackupFileInfo>> listBackups(
            @PathVariable String databaseName
    ) throws IOException {
        List<BackupFileInfo> backups = backupStorage.listWithInfo(null, databaseName);
        return ResponseEntity.ok(backups);
    }
}