package com.dentapinos.dataguard.entity;


import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;

/**
 * Политики для операции восстановления базы данных.
 * <p>
 * Определяет стратегию поведения при восстановлении данных:
 * как обрабатывать конфликты схем, дубликаты строк, нарушения внешних ключей и ошибки.
 *
 * @param schemaPolicy             политика проверки соответствия схемы целевой БД
 *                                 (STRICT_SCHEMA, RELAXED_SCHEMA, AUTO_CREATE_TABLES)
 * @param rowConflictPolicy        политика обработки конфликтов строк (дубликаты PK/UNIQUE)
 *                                 (FAIL_ON_CONFLICT, SKIP_ON_CONFLICT, OVERWRITE_ON_CONFLICT)
 * @param foreignKeyPolicy         политика работы с внешними ключами
 *                                 (ENFORCE_ALL, TEMP_DISABLE, SKIP_VIOLATIONS)
 * @param errorPolicy              общая политика обработки ошибок
 *                                 (FAIL_FAST, LOG_AND_CONTINUE)
 */
public record RestorePolicy(
        SchemaPolicy schemaPolicy,
        RowConflictPolicy rowConflictPolicy,
        ForeignKeyPolicy foreignKeyPolicy,
        ErrorPolicy errorPolicy
) {}

