bash
#!/usr/bin/env bash
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-https://keycloak.example.com}"
REALM="${REALM:-your-realm}"
CLIENT_ID="${CLIENT_ID:-nifi-prometheus-scraper}"
CLIENT_SECRET="${CLIENT_SECRET:-put-client-secret-here}"
NIFI_METRICS_URL="${NIFI_METRICS_URL:-https://nifi.example.com/nifi-api/controller/cluster}"
INSECURE="${INSECURE:-1}"

TOKEN_ENDPOINT="${KEYCLOAK_URL%/}/realms/${REALM}/protocol/openid-connect/token"

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

curl_tls=()
[[ "$INSECURE" == "1" ]] && curl_tls+=(-k)

b64url_decode() {
  local s="${1//-/+}"; s="${s//_//}"
  case $(( ${#s} % 4 )) in
    2) s="${s}==";;
    3) s="${s}=";;
  esac
  printf '%s' "$s" | base64 -d 2>/dev/null || true
}

echo "== Step 1: Token request =="
kc_code="$(curl -sS --location "${curl_tls[@]}" \
  -o "$tmp/kc.json" -w '%{http_code}' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "client_secret=$CLIENT_SECRET" \
  "$TOKEN_ENDPOINT" || true)"

echo "HTTP $kc_code"
head -c 2000 "$tmp/kc.json"; echo ""

[[ "$kc_code" == "200" ]] || { echo "STOP: token request failed"; exit 1; }

ACCESS_TOKEN="$(sed -nE 's/.*"access_token":"([^"]+)".*/\1/p' "$tmp/kc.json" | head -n1)"
[[ -n "$ACCESS_TOKEN" ]] || { echo "STOP: access_token not found"; exit 1; }
echo "Token length: ${#ACCESS_TOKEN}"
echo ""

echo "== Step 2: Token payload =="
b64url_decode "$(echo "$ACCESS_TOKEN" | cut -d'.' -f2)" > "$tmp/payload"
head -c 4000 "$tmp/payload"; echo ""
echo ""

for field in iss aud azp sub scope exp; do
  val="$(grep -oE "\"${field}\":[^,}]+" "$tmp/payload" | head -1 | sed "s/\"${field}\"://" || true)"
  echo "${field}: ${val:-NOT_FOUND}"
done
echo ""

echo "== Step 3: NiFi request =="
nf_code="$(curl -sS --location "${curl_tls[@]}" \
  -D "$tmp/nf.h" -o "$tmp/nf.b" -w '%{http_code}' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$NIFI_METRICS_URL" || true)"

echo "HTTP $nf_code"
grep -i '^WWW-Authenticate:' "$tmp/nf.h" 2>/dev/null || true
head -c 4000 "$tmp/nf.b"; echo ""
echo ""

echo "== Result =="
case "$nf_code" in
  200) echo "OK";;
  401) echo "401: check aud, iss, audience mapper, NiFi OIDC config";;
  403) echo "403: token accepted, missing roles. Check service account roles";;
  000|"") echo "No response. Check URL, network, firewall, TLS";;
  *) echo "Unexpected: HTTP $nf_code";;
esac
