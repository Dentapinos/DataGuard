package com.dentapinos.dataguard.utils;


import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.exception.DatabaseNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Находит в настройках БД по логическому имени и
 * возвращает её учетные данные ({@link DbCredentials}).
 * Если БД не найдена — выбрасывает {@link DatabaseNotFoundException}.
 */
@Component
@RequiredArgsConstructor
public class DatabaseConfigResolver {

    private final BackupDatabasesProperties backupDatabasesProperties;

    public DbCredentials resolveCredentials(String logicalDatabaseName) throws DatabaseNotFoundException {
        var dbOpt = backupDatabasesProperties.getDatabases().stream()
                .filter(db -> logicalDatabaseName.equals(db.getDatabaseName()))
                .findFirst();

        if (dbOpt.isEmpty()) {
            throw new DatabaseNotFoundException("База данных с именем '" + logicalDatabaseName + "' не настроена на сервере");
        }

        var db = dbOpt.get();
        return new DbCredentials(db.getUrl(), db.getUsername(), db.getPassword());
    }
}
