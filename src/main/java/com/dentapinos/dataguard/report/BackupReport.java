package com.dentapinos.dataguard.report;

import com.dentapinos.dataguard.enums.BackupStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Подробный отчет о резервном копировании")
public record BackupReport(
        @Schema(description = "Время начала резервного копирования", example = "2026-06-14T10:00:00Z") Instant startedAt,
        @Schema(description = "Время завершения резервного копирования", example = "2026-06-14T10:05:30Z") Instant finishedAt,
        @Schema(description = "Имя исходной базы данных") String database,
        @Schema(description = "Тип движка базы данных") String engine,
        @Schema(description = "Статус резервного копирования") BackupStatus status,
        @Schema(description = "Детали сводки резервного копирования") BackupSummary summary
) { }
