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
ф#!/usr/bin/env bash
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-https://keycloak.example.com}"
REALM="${REALM:-your-realm}"
CLIENT_ID="${CLIENT_ID:-nifi-prometheus-scraper}"
CLIENT_SECRET="${CLIENT_SECRET:-put-client-secret-here}"
NIFI_METRICS_URL="${NIFI_METRICS_URL:-https://nifi.example.com/nifi-api/controller/cluster}"

INSECURE="${INSECURE:-1}"   # 1 => -k, 0 => verify TLS

TOKEN_ENDPOINT="${KEYCLOAK_URL%/}/realms/${REALM}/protocol/openid-connect/token"
EXPECTED_ISS="${KEYCLOAK_URL%/}/realms/${REALM}"

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
kc_h="$tmp/kc.h"; kc_b="$tmp/kc.b"
nf_h="$tmp/nf.h"; nf_b="$tmp/nf.b"

curl_tls=()
[[ "$INSECURE" == "1" ]] && curl_tls+=(-k)

b64url_decode() {
  local s="${1//-/+}"; s="${s//_//}"
  case $(( ${#s} % 4 )) in
    2) s="${s}==";;
    3) s="${s}=";;
    1) printf ''; return 0;;
  esac
  printf '%s' "$s" | base64 -d 2>/dev/null || true
}

json_get_str() { # naive JSON string extractor (best effort)
  local key="$1" file="$2"
  sed -nE 's/.*"'"$key"'":[[:space:]]*"([^"]*)".*/\1/p' "$file" | head -n1
}
json_get_num() { # naive JSON number extractor (best effort)
  local key="$1" file="$2"
  sed -nE 's/.*"'"$key"'":[[:space:]]*([0-9]+).*/\1/p' "$file" | head -n1
}

echo "== Step 1: Keycloak token request =="
echo "POST $TOKEN_ENDPOINT"
kc_code="$(
  curl -sS --location "${curl_tls[@]}" \
    -D "$kc_h" -o "$kc_b" -w '%{http_code}' \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "client_secret=$CLIENT_SECRET" \
    "$TOKEN_ENDPOINT" || true
)"
echo "HTTP $kc_code"
echo "-- headers (first 40 lines)"; sed -n '1,40p' "$kc_h" || true
echo "-- body (first 2000 chars)"; head -c 2000 "$kc_b" || true; echo; echo

[[ "$kc_code" == "200" ]] || { echo "STOP: token request failed"; exit 1; }

ACCESS_TOKEN="$(sed -nE 's/.*"access_token":"([^"]+)".*/\1/p' "$kc_b" | head -n1)"
[[ -n "$ACCESS_TOKEN" ]] || { echo "STOP: access_token not found"; exit 1; }
echo "Token received: length=${#ACCESS_TOKEN}"
echo

echo "== Step 2: JWT quick decode (payload) =="
payload_b64="$(cut -d'.' -f2 <<<"$ACCESS_TOKEN" || true)"
payload_json="$tmp/payload.json"
b64url_decode "$payload_b64" > "$payload_json"

echo "-- payload (raw, first 4000 chars)"; head -c 4000 "$payload_json" || true; echo; echo

iss="$(json_get_str iss "$payload_json")"
azp="$(json_get_str azp "$payload_json")"
sub="$(json_get_str sub "$payload_json")"
aud_frag="$(grep -oE '"aud":[^,}]+' "$payload_json" | head -n1 || true)"
exp="$(json_get_num exp "$payload_json")"

echo "iss:  ${iss:-<not found>}  (expected: $EXPECTED_ISS)"
echo "azp:  ${azp:-<not found>}"
echo "sub:  ${sub:-<not found>}"
echo "aud:  ${aud_frag:-<not found>}"
if [[ -n "${exp:-}" ]]; then
  now="$(date +%s)"
  echo "exp:  $exp"
  if date -u -d "@$exp" >/dev/null 2>&1; then
    echo "exp_utc: $(date -u -d "@$exp" '+%Y-%m-%d %H:%M:%S UTC')"
  fi
  echo "ttl:  $((exp-now))s"
fi
echo

echo "== Step 3: Call NiFi with Bearer token =="
echo "GET $NIFI_METRICS_URL"
nf_code="$(
  curl -sS --location "${curl_tls[@]}" \
    -D "$nf_h" -o "$nf_b" -w '%{http_code}' \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Accept: application/json" \
    "$NIFI_METRICS_URL" || true
)"
echo "HTTP $nf_code"
echo "-- WWW-Authenticate"; grep -i '^WWW-Authenticate:' "$nf_h" || true
echo "-- headers (first 60 lines)"; sed -n '1,60p' "$nf_h" || true
echo "-- body (first 8000 chars)"; head -c 8000 "$nf_b" || true; echo; echo

echo "== Summary =="
case "$nf_code" in
  200) echo "OK: NiFi returned 200";;
  401) echo "FAIL: 401 Unauthorized (token rejected or not trusted)";;
  403) echo "FAIL: 403 Forbidden (token accepted, insufficient permissions)";;
  000|"") echo "FAIL: no HTTP response (network/TLS/DNS)";;
  *) echo "FAIL: HTTP $nf_code";;
esac
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
