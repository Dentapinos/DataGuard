package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.entity.storage.BackupFile;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Утилита для фильтрации таблиц из бэкапа.
 */
@UtilityClass
public class TableFilter {

    /**
     * Фильтрует данные бэкапа по указанному списку таблиц.
     * Если список пуст или null — возвращает оригинальный бэкап.
     *
     * @param backupFile оригинальный бэкап
     * @param tables список имен таблиц для восстановления (может быть null или пустым)
     * @return бэкап с отфильтрованными данными
     */
    public BackupFile filterTables(BackupFile backupFile, List<String> tables) {
        // Если список таблиц не указан, возвращаем оригинальный бэкап
        if (tables == null || tables.isEmpty()) {
            return backupFile;
        }

        Map<String, List<Map<String, Object>>> filteredData = new HashMap<>();
        Map<String, List<Map<String, Object>>> originalData = backupFile.data();

        for (String tableName : tables) {
            if (originalData.containsKey(tableName)) {
                filteredData.put(tableName, originalData.get(tableName));
            }
        }

        // Фильтруем и сохраняем порядок таблиц
        List<String> filteredTableOrder = backupFile.tableOrder() != null
                ? backupFile.tableOrder().stream()
                        .filter(tables::contains)
                        .collect(Collectors.toList())
                : null;

        return new BackupFile(
                backupFile.database(),
                backupFile.engine(),
                backupFile.schema(),
                Collections.unmodifiableMap(filteredData),
                filteredTableOrder
        );
    }
}
