package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.storage.BackupFile;

/**
 * Стратегия восстановления бэкапов.
 * <p>
 * Реализует паттерн Strategy для применения различных политик восстановления:
 * <ul>
 *   <li>STRICT — строгое совпадение схемы и данных</li>
 *   <li>SAFE_MERGE — мягкое слияние с пропуском конфликтов</li>
 *   <li>FORCE_REPLACE — агрессивная замена данных</li>
 *   <li>APPEND_ONLY — только добавление, существующие данные не трогаются</li>
 * </ul>
 *
 * @see com.dentapinos.dataguard.enums.RestoreMode
 */
public interface RestoreStrategy {

    /**
     * Выполняет восстановление бэкапа в целевую базу данных по заданной стратегии.
     *
     * @param dbCredentials   учетные данные для подключения к целевой БД
     * @param backup          бэкап-файл с данными для восстановления
     * @param targetDatabase  имя целевой базы данных
     * @param policy          политика восстановления (стратегия)
     * @param stats           объект для сбора статистики операции
     */
    void restore(DbCredentials dbCredentials,
                 BackupFile backup,
                 String targetDatabase,
                 RestorePolicy policy,
                 RestoreStats stats, String fileName);
}
