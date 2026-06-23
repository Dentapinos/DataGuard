package com.dentapinos.dataguard.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Статистика операции экспорта (создания резервной копии).
 */
@Data
public class ExportStats {
    int tablesTotal;
    int tablesProcessed;
    int tablesFailed;
    int tablesSkipped;
    long totalRows;
    Map<String, Long> rowsPerTable = new HashMap<>();
}
