#!/usr/bin/env bash
#
# Local manual test for payment-confirmation-catch-all-integrity-mapping: the gateway webhook path.
#
#   ./webhook-local-test.sh [base-url]        (base-url defaults to http://localhost:8090)
#
# The backend at base-url must be running THIS branch's code. For the signed checks, the local
# webhook signing key must be exported in this shell under the same variable name the backend
# reads (see WH_VAR below) and must be the value the backend was started with. It is read from the
# environment only and never printed.
#
# Without that variable the script still creates its fixtures and runs the unsigned check, and
# skips everything that needs a signature.
#
# It creates three throwaway agreements (dummy parties at example.com) and one payment order each,
# and removes the payment orders again on exit. The agreements stay; one of them ends PAID.

set -euo pipefail

BASE="${1:-http://localhost:8090}"
WH_VAR="RZP_WEBHOOK_SECRET"
WH_KEY="${!WH_VAR:-}"
PG=(docker exec -i agreement-mitra-postgres-1 psql -U agreementmitra -d agreementmitra -Atq)
pass=0 fail=0
order_ids=()

check() { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf 'PASS  %s (%s)\n' "$1" "$3"; pass=$((pass + 1))
  else
    printf 'FAIL  %s - expected %s, got %s\n' "$1" "$2" "$3"; fail=$((fail + 1))
  fi
}

sql() { "${PG[@]}" -c "$1"; }

cleanup() {
  for id in "${order_ids[@]:-}"; do
    [ -n "$id" ] && sql "DELETE FROM payment_order WHERE id = '$id'" >/dev/null || true
  done
}
trap cleanup EXIT

# --- fixtures ----------------------------------------------------------------------------------

csrf="$(curl -s -D - -o /dev/null "$BASE/api/auth/csrf" \
  | tr -d '\r' | sed -n 's/^[Ss]et-[Cc]ookie: \([^=]*XSRF-TOKEN\)=\([^;]*\).*/\1=\2/p' | head -1)"
[ -n "$csrf" ] || { echo "could not get a CSRF token from $BASE - is the backend up?" >&2; exit 2; }

create_agreement() {
  curl -s -X POST "$BASE/api/agreements" \
    -H 'Content-Type: application/json' -H "Cookie: $csrf" -H "X-XSRF-TOKEN: ${csrf#*=}" \
    -d '{"state":"TG","type":"residential","propertyAddress":"12 Test Road, Hyderabad",
         "monthlyRent":"25000.00","securityDeposit":"50000.00",
         "startDate":"2026-01-01","endDate":"2026-12-01",
         "signers":[
           {"firstName":"Asha","lastName":"Owner","fatherName":"Ravi Owner",
            "currentAddress":"1 A St","email":"asha@example.com","role":"OWNER"},
           {"firstName":"Tara","lastName":"Tenant","fatherName":"Hari Tenant",
            "currentAddress":"3 C St","email":"tara@example.com","role":"TENANT"}]}' \
    | grep -o '"id":"[0-9a-f-]\{36\}"' | head -1 | cut -d'"' -f4
}

new_order() { # new_order <agreement-id>  -> prints the provider order id
  local id; id="$(uuidgen | tr 'A-Z' 'a-z')"
  sql "INSERT INTO payment_order (id, agreement_id, provider, provider_order_id, receipt,
         amount_minor_units, currency, status, created_at, surplus)
       VALUES ('$id', '$1', 'razorpay', 'order_$id', '$id', 49900, 'INR', 'CREATED', now(), false)" >/dev/null
  order_ids+=("$id")
  echo "order_$id"
}

state_of() { sql "SELECT payment_state FROM agreement WHERE id = '$1'"; }
order_of() { sql "SELECT status || '/' || coalesce(provider_payment_id, 'null') FROM payment_order WHERE provider_order_id = '$1'"; }

body_for() { # body_for <order> <payment-id>
  printf '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":49900,"currency":"INR","status":"captured"}}}}' "$2" "$1"
}

deliver() { # deliver <body> [signature]  -> prints the HTTP status
  curl -s -o "$resp" -w '%{http_code}' -X POST "$BASE/api/webhooks/razorpay" \
    -H 'Content-Type: application/json' ${2:+-H "X-Razorpay-Signature: $2"} -d "$1"
}

sign() { printf '%s' "$1" | openssl dgst -sha256 -hmac "$WH_KEY" -hex | awk '{print $NF}'; }

resp="$(mktemp)"
A="$(create_agreement)"; B="$(create_agreement)"; C="$(create_agreement)"
for x in "$A" "$B" "$C"; do
  [ -n "$x" ] || { echo "could not create a test agreement at $BASE" >&2; exit 2; }
done
OA="$(new_order "$A")"; OB="$(new_order "$B")"; OC="$(new_order "$C")"
suffix="$(uuidgen | tr -d '-' | cut -c1-12)"
LONG="$(printf 'pay_TOOLONG%s%s' "$suffix" "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX" | cut -c1-65)"
GOOD="pay_LOCAL$suffix"
echo "fixtures: agreements ${A%%-*}.. ${B%%-*}.. ${C%%-*}..   base $BASE"
echo

# --- 0. an unsigned webhook is still rejected (needs no signing key) ----------------------------
check "unsigned webhook is rejected" 401 "$(deliver "$(body_for "$OA" "$GOOD")")"
check "  ...and nothing changed" "UNPAID" "$(state_of "$A")"

if [ -z "$WH_KEY" ]; then
  echo
  echo "SKIP  everything signed: $WH_VAR is not exported in this shell."
  echo "$pass passed, $fail failed, signed checks skipped"
  exit $(( fail > 0 ))
fi

# --- 1. a refusal that is not a duplicate: 500, nothing recorded, same again on redelivery ------
body="$(body_for "$OA" "$LONG")"; sig="$(sign "$body")"
check "over-long payment id -> webhook NOT acknowledged" 500 "$(deliver "$body" "$sig")"
if grep -q -e "$LONG" -e 22001 -e 'character varying' -e PaymentRecordingFailed "$resp"; then
  check "  response body leaks nothing" "clean" "LEAKED"
else
  check "  response body leaks nothing" "clean" "clean"
fi
check "  order still outstanding" "CREATED/null" "$(order_of "$OA")"
check "  agreement still unpaid" "UNPAID" "$(state_of "$A")"
check "redelivery gives the same answer" 500 "$(deliver "$body" "$sig")"
check "  ...and still nothing recorded" "CREATED/null" "$(order_of "$OA")"

# --- 2. the happy path is untouched --------------------------------------------------------------
body="$(body_for "$OB" "$GOOD")"
check "normal payment -> acknowledged" 202 "$(deliver "$body" "$(sign "$body")")"
check "  order paid" "PAID/$GOOD" "$(order_of "$OB")"
check "  agreement paid" "PAID" "$(state_of "$B")"
check "redelivery is acknowledged and changes nothing" 202 "$(deliver "$body" "$(sign "$body")")"

# --- 3. a TRUE duplicate is still acknowledged, and still records nothing ------------------------
body="$(body_for "$OC" "$GOOD")"
check "same payment id on another agreement -> acknowledged as a duplicate" 202 "$(deliver "$body" "$(sign "$body")")"
check "  that order is not paid" "CREATED/null" "$(order_of "$OC")"
check "  that agreement is not paid" "UNPAID" "$(state_of "$C")"

echo
echo "$pass passed, $fail failed"
echo
echo "Now look at the backend log. You should see, for the failing order (last 4 chars ${OA: -4}):"
echo "  ERROR ... PaymentOrderService : Payment recording failed for order ****${OA: -4}: the database refused the write (rule unnamed, SQL state 22001)"
echo "and the long payment id must appear nowhere:"
echo "  grep -c '$LONG' <your backend log>     # expect 0"
exit $(( fail > 0 ))
