package com.dentapinos.dataguard.service;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.service.engine.BackupEngine;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.storage.ZipBackupStorageService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BackupFacade {

    private final BackupEngine mySqlBackupEngine;       // реализация под MySQL
    private final ZipBackupStorageService backupStorageService;
    private final DatabaseMetadataReader mySqlMetadataReader;
    private final BackupStorage backupStorage;

    /**
     * Ручной бэкап выбранных таблиц (то, что вызывает контроллер).
     */
    public BackupEnvelope backupMySql(DbCredentials credentials,
                                      String database,
                                      List<String> tablesToInclude) {
        return mySqlBackupEngine.backup(credentials, database, tablesToInclude);
    }

    /**
     * Автоматический бэкап всех таблиц БД и сохранение в хранилище.
     * Возвращает имя сохранённого файла бэкапа.
     */
    public String backupAndStore(String database,
                                 DbCredentials credentials) throws IOException {

        // 1. Получаем список всех таблиц в БД
        List<String> allTables = mySqlMetadataReader.listTables(credentials, database);

        // 2. Делаем полный бэкап по этому списку
        BackupEnvelope envelope = mySqlBackupEngine.backup(credentials, database, allTables);

        // 3. Сохраняем бэкап (ZIP + репорт) через storage‑сервис
        return backupStorageService.storeBackupWithReport(database, envelope);
    }

    /**
     * Храним бэкап (используется в ручном сценарии, когда контроллер
     * сначала делает backupMySql, а потом сохраняет).
     */
    public String storeBackup(BackupEnvelope envelope, String database) throws IOException {
        return backupStorageService.storeBackupWithReport(database, envelope);
    }

    /**
     * Загрузка бэкапа из хранилища (при restore и т.п.).
     */
    public void loadBackup(String database,
                                 String backupName,
                                 @Nullable BackupTier tier,
                                 OutputStream out) throws IOException {

        try (out; InputStream in = backupStorage.load(tier, database, backupName)) {
            StreamUtils.copy(in, out);
        }
    }

    /**
     * Получить список таблиц БД (используется контроллером / UI).
     */
    public List<String> listTablesMySql(DbCredentials credentials, String database) {
        return mySqlMetadataReader.listTables(credentials, database);
    }
}