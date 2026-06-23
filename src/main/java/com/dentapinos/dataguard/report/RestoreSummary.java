package com.dentapinos.dataguard.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Сводка операции восстановления")
public record RestoreSummary(
        @Schema(description = "Общее количество таблиц", example = "10") long tablesTotal,
        @Schema(description = "Количество успешно обработанных таблиц", example = "8") long tablesProcessed,
        @Schema(description = "Количество неудачных таблиц", example = "1") long tablesFailed,
        @Schema(description = "Количество пропущенных таблиц", example = "1") long tablesSkipped,
        @Schema(description = "Количество вставленных строк", example = "500") long rowsInserted,
        @Schema(description = "Количество обновленных строк", example = "300") long rowsUpdated,
        @Schema(description = "Количество пропущенных строк", example = "200") long rowsSkipped,
        @Schema(description = "Сопоставление имен таблиц с количеством вставленных строк") Map<String, Long> rowsPerTableInserted,
        @Schema(description = "Сопоставление имен таблиц с количеством обновленных строк") Map<String, Long> rowsPerTableUpdated,
        @Schema(description = "Сопоставление имен таблиц с количеством пропущенных строк") Map<String, Long> rowsPerTableSkipped
) {}
