export KEYCLOAK_URL='https://keycloak.example.com' \
       REALM='your-realm' \
       CLIENT_ID='nifi-metrics' \
       CLIENT_SECRET='your-secret-here' \
       NIFI_METRICS_URL='https://nifi.example.com/nifi-api/controller/cluster' \
       INSECURE='1'
---
#!/usr/bin/env bash
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-https://keycloak.example.com}"
REALM="${REALM:-your-realm}"
CLIENT_ID="${CLIENT_ID:-nifi-metrics}"
CLIENT_SECRET="${CLIENT_SECRET:-put-client-secret-here}"
NIFI_METRICS_URL="${NIFI_METRICS_URL:-https://nifi.example.com/nifi-api/controller/cluster}"
INSECURE="${INSECURE:-1}"

curl_tls=()
[[ "$INSECURE" == "1" ]] && curl_tls+=(-k)

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

ERRORS=0

b64url_decode() {
  local s="${1//-/+}"; s="${s//_//}"
  case $(( ${#s} % 4 )) in
    2) s="${s}==";;
    3) s="${s}=";;
  esac
  printf '%s' "$s" | base64 -d 2>/dev/null || true
}

echo "== Check 1: Variables =="
echo "KEYCLOAK_URL:     [${KEYCLOAK_URL}]"
echo "REALM:            [${REALM}]"
echo "CLIENT_ID:        [${CLIENT_ID}]"
echo "CLIENT_SECRET:    [${CLIENT_SECRET:0:4}...] (length=${#CLIENT_SECRET})"
echo "NIFI_METRICS_URL: [${NIFI_METRICS_URL}]"

for var in KEYCLOAK_URL REALM CLIENT_ID CLIENT_SECRET NIFI_METRICS_URL; do
  val="${!var}"
  if [[ -z "$val" || "$val" == *" "* ]]; then
    echo "FAIL: $var is empty or contains spaces: [$val]"
    ERRORS=$((ERRORS+1))
  fi
done
echo ""

echo "== Check 2: Keycloak reachable =="
REALM_URL="${KEYCLOAK_URL%/}/realms/${REALM}"
realm_code="$(curl -sS "${curl_tls[@]}" -o "$tmp/realm.json" -w '%{http_code}' "$REALM_URL" || true)"
echo "GET $REALM_URL => HTTP $realm_code"

if [[ "$realm_code" == "000" ]]; then
  echo "FAIL: cannot connect to Keycloak (DNS/network/TLS)"
  ERRORS=$((ERRORS+1))
elif [[ "$realm_code" == "404" ]]; then
  echo "FAIL: realm '${REALM}' not found"
  head -c 500 "$tmp/realm.json" 2>/dev/null; echo ""
  ERRORS=$((ERRORS+1))
elif [[ "$realm_code" != "200" ]]; then
  echo "FAIL: unexpected response"
  head -c 500 "$tmp/realm.json" 2>/dev/null; echo ""
  ERRORS=$((ERRORS+1))
else
  echo "OK: realm exists"
fi
echo ""

[[ $ERRORS -gt 0 ]] && { echo "STOP: fix errors above first"; exit 1; }

echo "== Check 3: Token request =="
TOKEN_ENDPOINT="${KEYCLOAK_URL%/}/realms/${REALM}/protocol/openid-connect/token"
echo "POST $TOKEN_ENDPOINT"

kc_code="$(curl -sS --location "${curl_tls[@]}" \
  -o "$tmp/kc.json" -D "$tmp/kc.h" -w '%{http_code}' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "client_secret=$CLIENT_SECRET" \
  "$TOKEN_ENDPOINT" || true)"

echo "HTTP $kc_code"

if [[ "$kc_code" != "200" ]]; then
  echo "-- response body --"
  head -c 2000 "$tmp/kc.json" 2>/dev/null; echo ""

  err="$(sed -nE 's/.*"error":"([^"]+)".*/\1/p' "$tmp/kc.json" | head -1 || true)"
  err_desc="$(sed -nE 's/.*"error_description":"([^"]+)".*/\1/p' "$tmp/kc.json" | head -1 || true)"

  echo ""
  echo "error:             ${err:-unknown}"
  echo "error_description: ${err_desc:-unknown}"
  echo ""

  case "$err" in
    invalid_client)
      echo "DIAGNOSIS: client_id '${CLIENT_ID}' not found in realm '${REALM}'"
      echo "  OR client_secret is wrong"
      echo "  OR Client authentication is OFF (must be ON)"
      ;;
    unauthorized_client)
      echo "DIAGNOSIS: grant_type=client_credentials not allowed"
      echo "ACTION: enable 'Service accounts roles' for client '${CLIENT_ID}'"
      ;;
    invalid_grant)
      echo "DIAGNOSIS: grant type rejected"
      echo "ACTION: enable 'Service accounts roles' for client '${CLIENT_ID}'"
      ;;
    *)
      echo "DIAGNOSIS: unknown error, see response body above"
      ;;
  esac
  exit 1
fi

ACCESS_TOKEN="$(sed -nE 's/.*"access_token":"([^"]+)".*/\1/p' "$tmp/kc.json" | head -n1)"
[[ -n "$ACCESS_TOKEN" ]] || { echo "FAIL: access_token not found in response"; exit 1; }
echo "OK: token received (length=${#ACCESS_TOKEN})"
echo ""

echo "== Check 4: Token payload =="
b64url_decode "$(echo "$ACCESS_TOKEN" | cut -d'.' -f2)" > "$tmp/payload"

for field in iss aud azp sub scope exp preferred_username; do
  val="$(grep -oE "\"${field}\":[^,}]+" "$tmp/payload" | head -1 | sed "s/\"${field}\"://" || true)"
  echo "${field}: ${val:-NOT_FOUND}"
done

echo "realm_access: $(grep -oE '"realm_access":\{[^}]*\}' "$tmp/payload" || echo 'NOT_FOUND')"
echo "resource_access: $(grep -oE '"resource_access":\{.*\}' "$tmp/payload" | head -c 500 || echo 'NOT_FOUND')"
echo ""

echo "== TOKEN (full) =="
echo "$ACCESS_TOKEN"
echo ""

echo "== Check 5: NiFi request =="
echo "GET $NIFI_METRICS_URL"

nf_code="$(curl -sS --location "${curl_tls[@]}" \
  -D "$tmp/nf.h" -o "$tmp/nf.b" -w '%{http_code}' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$NIFI_METRICS_URL" || true)"

echo "HTTP $nf_code"

if [[ "$nf_code" != "200" ]]; then
  echo "-- WWW-Authenticate --"
  grep -i '^WWW-Authenticate' "$tmp/nf.h" 2>/dev/null || echo "(not present)"
  echo "-- response headers --"
  cat "$tmp/nf.h" 2>/dev/null || true
  echo "-- response body (first 2000 chars) --"
  head -c 2000 "$tmp/nf.b" 2>/dev/null; echo ""
  echo ""

  case "$nf_code" in
    401)
      echo "DIAGNOSIS: NiFi rejected the token"
      aud="$(grep -oE '"aud":[^,}]+' "$tmp/payload" | head -1 || true)"
      iss="$(grep -oE '"iss":"[^"]+"' "$tmp/payload" | head -1 || true)"
      echo "  token aud: ${aud:-NOT_FOUND}"
      echo "  token iss: ${iss:-NOT_FOUND}"
      echo ""
      echo "ACTIONS:"
      echo "  1. Check nifi.security.user.oidc.client.id in nifi.properties"
      echo "  2. That client_id must appear in 'aud' field above"
      echo "  3. If not -> add Audience mapper in Keycloak for client '${CLIENT_ID}'"
      echo "  4. Copy the full token above and test manually:"
      echo "     curl -k -H 'Authorization: Bearer <token>' '${NIFI_METRICS_URL}'"
      ;;
    403)
      echo "DIAGNOSIS: token accepted but insufficient permissions"
      echo "ACTION: assign NiFi roles to service account of '${CLIENT_ID}'"
      ;;
    000|"")
      echo "DIAGNOSIS: cannot connect to NiFi (DNS/network/TLS/firewall)"
      ;;
    302)
      echo "DIAGNOSIS: NiFi redirects to login page (token not recognized as valid)"
      echo "  Same as 401 — check aud, iss, audience mapper"
      ;;
    *)
      echo "DIAGNOSIS: unexpected HTTP $nf_code, see response above"
      ;;
  esac
  exit 1
fi

echo "OK: NiFi returned 200"
head -c 2000 "$tmp/nf.b"; echo ""
