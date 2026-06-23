package com.dentapinos.dataguard.dto;

import java.util.List;

/**
 * Результат анализа совместимости схемы бэкапа и целевой БД.
 */
public record SchemaCompatibilityAnalysisDto(
        boolean compatibleStrict,              // полностью совместим в STRICT_SCHEMA
        boolean compatibleRelaxed,             // можно восстановить в RELAXED_SCHEMA
        List<String> blockingIssuesStrict,     // критичные проблемы для STRICT
        List<String> blockingIssuesRelaxed,    // критичные проблемы даже для RELAXED (если появятся)
        List<String> warnings                  // некритичные замечания / инфо
) {}
