package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.exception.InvalidRestoreModeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Фабрика стратегий восстановления.
 * <p>
 * Выбирает и возвращает соответствующую стратегию на основе указанного {@link RestoreMode}.
 * Пример использования:
 * <pre>{@code
 * RestoreStrategy strategy = strategyFactory.getStrategy(RestoreMode.FORCE_REPLACE);
 * strategy.restore(credentials, backup, targetDatabase, policy, stats);
 * }</pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RestoreStrategyFactory {

    private final StrictRestoreStrategy strictRestoreStrategy;
    private final SafeMergeRestoreStrategy safeMergeRestoreStrategy;
    private final ForceReplaceRestoreStrategy forceReplaceRestoreStrategy;
    private final AppendOnlyRestoreStrategy appendOnlyRestoreStrategy;
    private final DryRunRestoreStrategy dryRunRestoreStrategy;
    private final SafeSchemaCheckRestoreStrategy safeSchemaCheckRestoreStrategy;
    private final UpsertAllRestoreStrategy upsertAllRestoreStrategy;

    /**
     * Получает стратегию восстановления на основе указанного режима.
     *
     * @param mode режим восстановления (STRICT, SAFE_MERGE, FORCE_REPLACE, APPEND_ONLY)
     * @return соответствующая реализация {@link RestoreStrategy}
     * @throws InvalidRestoreModeException если указан неизвестный режим
     */
    public RestoreStrategy getStrategy(RestoreMode mode) {
        log.debug("[STRATEGY_FACTORY] Выбор стратегии для режима: {}", mode);
        return switch (mode) {
            case STRICT -> strictRestoreStrategy;
            case SAFE_MERGE -> safeMergeRestoreStrategy;
            case FORCE_REPLACE -> forceReplaceRestoreStrategy;
            case APPEND_ONLY -> appendOnlyRestoreStrategy;
            case DRY_RUN -> dryRunRestoreStrategy;
            case SAFE_SCHEMA_CHECK -> safeSchemaCheckRestoreStrategy;
            case UPSERT_ALL -> upsertAllRestoreStrategy;
            default -> throw new InvalidRestoreModeException("Неизвестный режим восстановления: " + mode);
        };
    }
}
