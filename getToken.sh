#!/usr/bin/env bash
#
# nifi_scrape.sh — получение токена через client_credentials и запрос метрик NiFi
#

set -euo pipefail

# ========================== КОНФИГУРАЦИЯ ==========================

KEYCLOAK_URL="https://keycloak.example.com"
REALM="your-realm"
CLIENT_ID="nifi-prometheus-scraper"
CLIENT_SECRET="вставь-сюда-секрет-клиента"

NIFI_METRICS_URL="https://nifi.example.com/nifi-api/controller/cluster"
# или для Prometheus-эндпоинта:
# NIFI_METRICS_URL="https://nifi.example.com/nifi-api/system-diagnostics"

# ==================================================================

TOKEN_ENDPOINT="${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token"

# ---------- Получение токена ----------
get_token() {
    local response
    response=$(curl \
        --silent \
        --fail \
        --show-error \
        --request POST \
        --header "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "grant_type=client_credentials" \
        --data-urlencode "client_id=${CLIENT_ID}" \
        --data-urlencode "client_secret=${CLIENT_SECRET}" \
        "${TOKEN_ENDPOINT}" \
    )

    # Извлекаем access_token из JSON-ответа
    local token
    token=$(echo "${response}" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

    if [[ -z "${token}" ]]; then
        echo "ОШИБКА: Не удалось получить токен" >&2
        echo "Ответ Keycloak: ${response}" >&2
        exit 1
    fi

    echo "${token}"
}

# ---------- Запрос метрик ----------
scrape_metrics() {
    local token="$1"

    curl \
        --silent \
        --fail \
        --show-error \
        --header "Authorization: Bearer ${token}" \
        "${NIFI_METRICS_URL}"
}

# ---------- Main ----------
main() {
    echo "→ Запрашиваю токен у Keycloak..." >&2
    ACCESS_TOKEN=$(get_token)
    echo "→ Токен получен (${#ACCESS_TOKEN} символов)" >&2

    echo "→ Запрашиваю метрики NiFi..." >&2
    scrape_metrics "${ACCESS_TOKEN}"
}

main "$@"
