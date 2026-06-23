package com.dentapinos.dataguard.service.restore.sqlbuilder;

import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.service.restore.SqlBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Фабрика для получения SqlBuilder на основе RowConflictPolicy.
 * <p>
 * Централизует выбор стратегии генерации SQL в зависимости от политики конфликта.
 */
@Component
@RequiredArgsConstructor
public class SqlBuilderFactory {

    private final FailOnConflictSqlBuilder failOnConflictSqlBuilder;
    private final SkipOnConflictSqlBuilder skipOnConflictSqlBuilder;
    private final OverwriteOnConflictSqlBuilder overwriteOnConflictSqlBuilder;

    /**
     * Получает SqlBuilder на основе политики конфликта.
     *
     * @param policy политика конфликта
     * @return соответствующий SqlBuilder
     */
    public SqlBuilder getSqlBuilder(RowConflictPolicy policy) {
        return switch (policy) {
            case FAIL_ON_CONFLICT -> failOnConflictSqlBuilder;
            case SKIP_ON_CONFLICT -> skipOnConflictSqlBuilder;
            case OVERWRITE_ON_CONFLICT -> overwriteOnConflictSqlBuilder;
            default -> throw new IllegalArgumentException("Неизвестная политика конфликта: " + policy);
        };
    }
}
