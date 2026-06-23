package com.dentapinos.dataguard.service.restore.order;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.exception.CircularDependencyException;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Сервис для управления порядком восстановления таблиц.
 * <p>
 * Основной метод: determineRestoreOrder() - определяет порядок таблиц для восстановления
 * на основе метаданных БД и графа зависимостей (топологическая сортировка).
 * <p>
 * Анализирует внешние ключи между таблицами и определяет порядок восстановления,
 * при котором сначала восстанавливаются родительские таблицы (без внешних ключей на другие),
 * а затем дочерние таблицы (с внешними ключами).
 * <p>
 * Пример:
 * - Таблица users (нет внешних ключей) -> восстанавливается первой
 * - Таблица orders (FK -> users.id) -> восстанавливается после users
 * - Таблица products (FK -> users.id) -> восстанавливается после users
 * <p>
 * Если обнаружены циклические зависимости, будет выброшено исключение CircularDependencyException.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestoreOrderService {

    private final DatabaseMetadataReader metadataReader;

    /**
     * Определяет порядок восстановления таблиц на основе графа зависимостей.
     * <p>
     * Шаги:
     * 1. Читает метаданные целевой базы данных
     * 2. Строит граф зависимостей на основе внешних ключей
     * 3. Выполняет топологическую сортировку для определения порядка
     * 4. Возвращает упорядоченный список таблиц
     *
     * @param dbCredentials учетные данные для подключения к БД
     * @param targetDatabase имя целевой базы данных
     * @param tablesToRestore список таблиц для восстановления (null = все таблицы)
     * @return упорядоченный список имён таблиц или null, если порядок не может быть определен
     */
    public List<String> determineRestoreOrder(
            DbCredentials dbCredentials,
            String targetDatabase,
            List<String> tablesToRestore
    ) {
        log.info("[RESTORE_ORDER] Определение порядка восстановления для БД: {}, tables: {}", 
                targetDatabase, tablesToRestore);

        // Строим список таблиц для восстановления
        List<String> tablesToUse = resolveTablesToRestore(dbCredentials, targetDatabase, tablesToRestore);
        
        if (tablesToUse.isEmpty()) {
            log.warn("[RESTORE_ORDER] Нет таблиц для восстановления в БД: {}", targetDatabase);
            return List.of();
        }

        log.info("[RESTORE_ORDER] Таблицы для восстановления: {}", tablesToUse);

        try {
            // Читаем метаданные
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, targetDatabase, tablesToUse);
            
            // Определяем порядок на основе графа зависимостей
            List<String> restoreOrder = getRestoreOrder(schema, tablesToUse);
            
            log.info("[RESTORE_ORDER] Определен порядок восстановления: {}", restoreOrder);
            return restoreOrder;
        } catch (CircularDependencyException e) {
            log.error("[RESTORE_ORDER] Ошибка при определении порядка восстановления (циклическая зависимость): {}", e.getMessage());
            log.warn("[RESTORE_ORDER] Возвращаем null - используем порядок из бэкапа");
            return null;
        } catch (Exception e) {
            log.error("[RESTORE_ORDER] Ошибка при определении порядка восстановления", e);
            log.warn("[RESTORE_ORDER] Возвращаем null - используем порядок из бэкапа");
            return null;
        }
    }

    /**
     * Определяет порядок восстановления таблиц на основе графа зависимостей.
     * <p>
     * Использует топологическую сортировку для обработки внешних ключей:
     * сначала восстанавливаются родительские таблицы, затем дочерние.
     *
     * @param schema метаданные схемы БД
     * @param tablesToRestore список таблиц для восстановления
     * @return упорядоченный список имён таблиц
     */
    private List<String> getRestoreOrder(SchemaMeta schema, List<String> tablesToRestore) {
        if (tablesToRestore == null || tablesToRestore.isEmpty()) {
            return List.of();
        }

        log.info("=== Определение порядка восстановления таблиц ===");
        log.info("Целевые таблицы для восстановления: {}", tablesToRestore);
        
        // Создаем подмножество таблиц, которые нужно восстановить
        Set<String> targetTables = new HashSet<>(tablesToRestore);
        
        // Построение графа зависимостей для целевых таблиц
        Map<String, Set<String>> dependencyGraph = buildDependencyGraph(schema, targetTables);
        
        log.info("Граф зависимостей:");
        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            log.info("  {} -> {}", entry.getKey(), entry.getValue());
        }
        
        // Топологическая сортировка
        List<String> order = topologicalSort(dependencyGraph, targetTables);
        
        log.info("Определенный порядок восстановления: {}", order);
        log.info("=== Определение порядка завершено ===");
        
        return order;
    }

    /**
     * Строит граф зависимостей для указанных таблиц.
     * <p>
     * Граф представляет собой мапу: ключ - имя таблицы, значение - список таблиц, от которых зависит ключ.
     * Например, если orders имеет FK -> users, то dependencyGraph.get("orders") содержит "users".
     *
     * @param schema метаданные схемы БД
     * @param targetTables множества целевых таблиц для восстановления
     * @return граф зависимостей
     */
    private Map<String, Set<String>> buildDependencyGraph(SchemaMeta schema, Set<String> targetTables) {
        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        
        // Инициализируем граф для всех целевых таблиц
        for (String table : targetTables) {
            dependencyGraph.put(table, new HashSet<>());
        }
        
        // Обходим все целевые таблицы и ищем их внешние ключи
        for (TableMeta tableMeta : schema.tables()) {
            String tableName = tableMeta.name();
            
            // Если таблица не входит в целевые - пропускаем
            if (!targetTables.contains(tableName)) {
                continue;
            }
            
            log.debug("Обработка таблицы {} с {} внешними ключами", tableName, tableMeta.foreignKeys().size());
            
            // Обходим внешние ключи таблицы
            for (com.dentapinos.dataguard.entity.ForeignKeyMeta fk : tableMeta.foreignKeys()) {
                String referencedTable = fk.referencedTable();
                
                // Если внешний ключ ссылается на таблицу, которая также должна быть восстановлена
                if (targetTables.contains(referencedTable)) {
                    dependencyGraph.get(tableName).add(referencedTable);
                    log.debug("  FK {} -> {} (referencedTable: {})", fk.name(), tableName, referencedTable);
                } else {
                    log.debug("  FK {} -> {} (referencedTable: {} - не в целевых таблицах)", fk.name(), tableName, referencedTable);
                }
            }
        }
        
        return dependencyGraph;
    }

    /**
     * Выполняет топологическую сортировку графа зависимостей.
     * <p>
     * Использует алгоритм Kahn's algorithm для топологической сортировки.
     * Если обнаружены циклические зависимости, выбрасывает CircularDependencyException.
     *
     * @param dependencyGraph граф зависимостей (table -> set of tables it depends on)
     * @param allTables все целевые таблицы
     * @return упорядоченный список таблиц для восстановления
     * @throws CircularDependencyException если обнаружены циклические зависимости
     */
    private List<String> topologicalSort(Map<String, Set<String>> dependencyGraph, Set<String> allTables) {
        // Вычисляем входящую степень (in-degree) для каждой таблицы
        // in-degree = количество таблиц, от которых зависит данная таблица
        Map<String, Integer> inDegree = new HashMap<>();
        for (String table : allTables) {
            int degree = dependencyGraph.getOrDefault(table, new HashSet<>()).size();
            inDegree.put(table, degree);
            log.debug("Initial in-degree for {}: {}", table, degree);
        }
        
        // Очередь для обработки таблиц с in-degree = 0 (не зависят от других целевых таблиц)
        Queue<String> queue = new LinkedList<>();
        for (String table : allTables) {
            if (inDegree.getOrDefault(table, 0) == 0) {
                queue.add(table);
                log.debug("Table {} has in-degree 0, adding to queue", table);
            }
        }
        
        List<String> result = new ArrayList<>();
        int processedCount = 0;
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            processedCount++;
            
            log.debug("Processing table: {} (in-degree: {})", current, inDegree.get(current) - 1);
            
            // Уменьшаем in-degree для таблиц, которые зависят от текущей
            for (String table : allTables) {
                if (dependencyGraph.get(table) != null && dependencyGraph.get(table).contains(current)) {
                    int newDegree = inDegree.get(table) - 1;
                    inDegree.put(table, newDegree);
                    
                    if (newDegree == 0) {
                        queue.add(table);
                        log.debug("Table {} now has in-degree 0, adding to queue", table);
                    }
                }
            }
        }
        
        // Если не все таблицы были обработаны, значит есть циклическая зависимость
        if (processedCount != allTables.size()) {
            List<String> unprocessed = new ArrayList<>();
            for (String table : allTables) {
                if (!result.contains(table)) {
                    unprocessed.add(table);
                }
            }
            
            String cycleInfo = detectCycle(dependencyGraph, unprocessed);
            throw new CircularDependencyException(
                    "Обнаружены циклические зависимости между таблицами: " + cycleInfo,
                    cycleInfo
            );
        }
        
        return result;
    }
    
    /**
     * Пытается определить и описать циклическую зависимость.
     *
     * @param dependencyGraph граф зависимостей
     * @param remainingTables таблицы, которые не удалось обработать
     * @return описание цикла
     */
    private String detectCycle(Map<String, Set<String>> dependencyGraph, List<String> remainingTables) {
        if (remainingTables.isEmpty()) {
            return "неизвестно (все таблицы обработаны)";
        }
        
        // Простой способ - перечисляем оставшиеся таблицы
        return String.join(" <-> ", remainingTables);
    }

    /**
     * Разрешает список таблиц для восстановления.
     * <p>
     * Если список tablesToRestore пустой или null, возвращает все таблицы из БД.
     * Иначе возвращает указанный список, отфильтрованный по существующим таблицам.
     *
     * @param dbCredentials учетные данные для подключения к БД
     * @param targetDatabase имя целевой базы данных
     * @param tablesToRestore список таблиц для восстановления (может быть null)
     * @return список таблиц для восстановления
     */
    private List<String> resolveTablesToRestore(
            DbCredentials dbCredentials,
            String targetDatabase,
            List<String> tablesToRestore
    ) {
        if (tablesToRestore == null || tablesToRestore.isEmpty()) {
            log.info("Список таблиц не указан, читаем все таблицы из БД: {}", targetDatabase);
            return metadataReader.listTables(dbCredentials, targetDatabase);
        }
        
        log.info("Получен список таблиц для восстановления: {}", tablesToRestore);
        
        // Получаем список существующих таблиц
        List<String> existingTables = metadataReader.listTables(dbCredentials, targetDatabase);
        
        // Фильтруем указанные таблицы по существующим
        List<String> filtered = tablesToRestore.stream()
                .filter(existingTables::contains)
                .toList();
        
        if (filtered.size() < tablesToRestore.size()) {
            log.warn("Некоторые таблицы не существуют в БД: {}",
                    tablesToRestore.stream()
                            .filter(t -> !existingTables.contains(t))
                            .toList());
        }
        
        return filtered;
    }
}
