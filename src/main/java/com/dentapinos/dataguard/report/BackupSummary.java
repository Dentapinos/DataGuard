package com.dentapinos.dataguard.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Сводка операции резервного копирования")
public record BackupSummary(
        @Schema(description = "Общее количество таблиц", example = "10") int tablesTotal,
        @Schema(description = "Количество успешно обработанных таблиц", example = "8") int tablesProcessed,
        @Schema(description = "Количество неудачных таблиц", example = "1") int tablesFailed,
        @Schema(description = "Количество пропущенных таблиц", example = "1") int tablesSkipped,
        @Schema(description = "Общее количество обработанных строк", example = "1000") long totalRows,
        @Schema(description = "Сопоставление имен таблиц с количеством вставленных строк") Map<String, Long> rowsPerTable
) {}
