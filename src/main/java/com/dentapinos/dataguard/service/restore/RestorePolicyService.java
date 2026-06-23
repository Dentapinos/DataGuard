package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.exception.InvalidRestoreModeException;
import org.springframework.stereotype.Service;

/**
 * Сервис для определения политики восстановления (RestorePolicy) на основе режима восстановления (RestoreMode).
 * <p>
 * Предоставляет единую точку конфигурации политик для разных сценариев восстановления данных.
 */
@Service
public class RestorePolicyService {

    /**
     * Возвращает политику восстановления для указанного режима.
     *
     * @param mode режим восстановления ({@link RestoreMode})
     * @return {@link RestorePolicy} — политика восстановления с набором правил для:
     *         - проверки схемы;
     *         - обработки конфликтов строк;
     *         - работы с внешними ключами;
     *         - реакции на ошибки.
     * @throws InvalidRestoreModeException если передан неизвестный режим восстановления
     */
    public RestorePolicy policyFor(RestoreMode mode) {
        return switch (mode) {
            case STRICT -> new RestorePolicy(
                    SchemaPolicy.STRICT_SCHEMA,
                    RowConflictPolicy.FAIL_ON_CONFLICT,
                    ForeignKeyPolicy.ENFORCE_ALL,
                    ErrorPolicy.FAIL_FAST
            );
            case SAFE_MERGE, APPEND_ONLY, DRY_RUN, SAFE_SCHEMA_CHECK -> new RestorePolicy(
                    SchemaPolicy.RELAXED_SCHEMA,
                    RowConflictPolicy.SKIP_ON_CONFLICT,
                    ForeignKeyPolicy.SKIP_VIOLATIONS,
                    ErrorPolicy.LOG_AND_CONTINUE
            );
            case FORCE_REPLACE, UPSERT_ALL -> new RestorePolicy(
                    SchemaPolicy.RELAXED_SCHEMA,
                    RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                    ForeignKeyPolicy.TEMP_DISABLE,
                    ErrorPolicy.LOG_AND_CONTINUE
            );
            default -> throw new InvalidRestoreModeException("Неизвестный режим восстановления: " + mode);
        };
    }
}