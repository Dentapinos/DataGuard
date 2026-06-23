package com.dentapinos.dataguard.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Статистика операции восстановления данных из резервной копии.
 */
@Data
public class RestoreStats {
    private long tablesTotal;
    private long tablesProcessed;
    private long tablesFailed;
    private long tablesSkipped;

    private long rowsInserted;
    private long rowsUpdated;
    private long rowsSkipped;

    private Map<String, Long> rowsPerTableInserted = new HashMap<>();
    private Map<String, Long> rowsPerTableUpdated = new HashMap<>();
    private Map<String, Long> rowsPerTableSkipped = new HashMap<>();
}
