package com.dentapinos.dataguard.service.engine;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ExportStats;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.report.BackupSummary;
import com.dentapinos.dataguard.service.DatabaseDataExporter;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MySqlBackupEngine implements BackupEngine {

    private final DatabaseMetadataReader metadataReader;
    private final DatabaseDataExporter dataExporter;

    public BackupEnvelope backup(DbCredentials credentials,
                                 String database,
                                 List<String> tablesToInclude) {

        Instant startedAt = Instant.now();

        log.debug("[BACKUP_ENGINE] Запуск бэкапа: database={}, tablesToInclude={}", database, tablesToInclude);
        SchemaMeta schema = metadataReader.readSchema(credentials, database, tablesToInclude);
        log.debug("[BACKUP_ENGINE] Прочитана схема: tables={}", schema.tables().stream().map(TableMeta::name).toList());

        // Получаем порядок таблиц для бэкапа (такой же, как при экспорте)
        List<String> tableOrder = schema.tables().stream()
                .map(TableMeta::name)
                .toList();
        log.debug("[BACKUP_ENGINE] Порядок таблиц для экспорта: {}", tableOrder);

        ExportStats stats = new ExportStats();
        Map<String, List<Map<String, Object>>> data = dataExporter.exportData(credentials, schema, tableOrder, stats);
        log.info("[BACKUP_ENGINE] Экспорт завершен: tablesExported={}, totalRows={}, data.keySet()={}", stats.getTablesProcessed(), stats.getTotalRows(), data.keySet());

        Instant finishedAt = Instant.now();

        BackupSummary summary = new BackupSummary(
                stats.getTablesTotal(),
                stats.getTablesProcessed(),
                stats.getTablesFailed(),
                stats.getTablesSkipped(),
                stats.getTotalRows(),
                Map.copyOf(stats.getRowsPerTable())
        );

        BackupStatus status;
        if (summary.tablesFailed() == 0 && summary.tablesSkipped() == 0) {
            status = BackupStatus.SUCCESS;
        } else if (summary.tablesProcessed() > 0) {
            status = BackupStatus.COMPLETED_WITH_WARNINGS;
        } else {
            status = BackupStatus.FAILED;
        }

        BackupReport report = new BackupReport(
                startedAt,
                finishedAt,
                database,
                "mysql",
                status,
                summary
        );

        BackupFile backup = new BackupFile(database, "mysql", schema, data, tableOrder);

        return new BackupEnvelope(report, backup);
    }
}
