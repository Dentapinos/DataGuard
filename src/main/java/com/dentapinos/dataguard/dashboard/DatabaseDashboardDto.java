package com.dentapinos.dataguard.dashboard;

import java.util.List;

/**
 * Dashboard информация для одной базы данных.
 */
public record DatabaseDashboardDto(
    String databaseName,
    String displayName,
    List<TierInfo> tiers,
    long totalFileCount,
    long totalSizeBytes
) {}