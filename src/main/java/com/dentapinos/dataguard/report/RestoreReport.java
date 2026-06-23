package com.dentapinos.dataguard.report;

import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Подробный отчет о восстановлении")
public record RestoreReport(
        @Schema(description = "Время начала восстановления", example = "2026-06-14T10:00:00Z") Instant startedAt,
        @Schema(description = "Время завершения восстановления", example = "2026-06-14T10:05:30Z") Instant finishedAt,
        @Schema(description = "Имя целевой базы данных") String targetDatabase,
        @Schema(description = "Режим восстановления") RestoreMode mode,
        @Schema(description = "Статус восстановления") RestoreStatus status,
        @Schema(description = "Детали сводки восстановления") RestoreSummary summary
) {}
