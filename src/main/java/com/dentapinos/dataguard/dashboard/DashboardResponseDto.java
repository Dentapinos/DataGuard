package com.dentapinos.dataguard.dashboard;

import java.util.List;

/**
 * Полный ответ для dashboard.
 */
public record DashboardResponseDto(
    List<DatabaseDashboardDto> databases,
    ScheduleInfo schedule,
    StorageInfo storage,
    boolean safeMode
) {}