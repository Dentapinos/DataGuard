#!/bin/sh
# =============================================
# Health check script for DataGuard
# =============================================

# Проверка через Actuator (если включен)
if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    exit 0
fi

# Альтернативная проверка - через главную страницу или просто проверка порта
if curl -sf http://localhost:8080/swagger-ui.html > /dev/null 2>&1; then
    exit 0
fi

# Если ни один из методов не сработал - приложение не готово
exit 1
