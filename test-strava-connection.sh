#!/bin/bash
#
# Test script for Strava API connection.
# Tests OAuth token exchange, activity fetching, streams, and details.
#
# Usage:
#   ./test-strava-connection.sh
#
# IMPORTANT: Vul hieronder je Strava Client ID en Client Secret in.
# Te vinden op https://www.strava.com/settings/api

# Settings worden eerst uit .env geladen, daarna uit onderstaande defaults.
# .env-bestand (niet in git) heeft voorrang.

ENV_FILE="$(dirname "$0")/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

CLIENT_ID="${STRAVA_CLIENT_ID:-265769}"
CLIENT_SECRET="${STRAVA_CLIENT_SECRET:-88115b193d8d5f9934df65a8dd3a227458ae9645}"

# ---------------------------------------------------------------------------
# Niets wijzigen onder deze lijn
# ---------------------------------------------------------------------------

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0

pass() {
    PASS=$((PASS + 1))
    echo -e "  ${GREEN}✓${NC} $1"
}

fail() {
    FAIL=$((FAIL + 1))
    echo -e "  ${RED}✗${NC} $1"
    if [ -n "${2-}" ]; then
        echo -e "    ${YELLOW}Detail:${NC} $2"
    fi
}

section() {
    echo ""
    echo "========================================"
    echo "  $1"
    echo "========================================"
}

# ------------------------------------------------------------------
# Check credentials
# ------------------------------------------------------------------
section "Credentials check"

if [ -z "$CLIENT_ID" ] || [ -z "$CLIENT_SECRET" ]; then
    echo -e "${RED}Error: Strava Client ID en Client Secret niet gevonden.${NC}"
    echo ""
    echo "  Zet ze in een .env bestand in de projectroot:"
    echo "    STRAVA_CLIENT_ID=xxx"
    echo "    STRAVA_CLIENT_SECRET=xxx"
    echo ""
    echo "  Of exporteer ze als environment variabele."
    echo "  Te vinden op https://www.strava.com/settings/api"
    exit 1
fi

echo -e "  Client ID:     ${GREEN}${CLIENT_ID:0:4}${NC}..."

# ------------------------------------------------------------------
# Test Strava API base endpoint (no auth needed)
# ------------------------------------------------------------------
section "Strava API reachability"

STRAVA_BASE="https://www.strava.com/api/v3"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$STRAVA_BASE/athlete" 2>&1 || true)
if [ "$HTTP_CODE" = "401" ]; then
    pass "Strava API is reachable (returned 401 as expected - no token yet)"
else
    fail "Strava API returned HTTP $HTTP_CODE (expected 401)" "$HTTP_CODE"
fi

# ------------------------------------------------------------------
# Test token exchange (requires a code from OAuth flow)
# ------------------------------------------------------------------
section "OAuth token exchange"

echo ""
echo "  To test the OAuth token exchange, open this URL in your browser:"
echo ""
echo -e "  ${YELLOW}https://www.strava.com/oauth/authorize?client_id=${CLIENT_ID}&response_type=code&redirect_uri=http://localhost:8080/callback&approval_prompt=force&scope=activity:read_all${NC}"
echo ""
echo -n "  Paste the 'code' parameter from the redirect URL: "
read -r AUTH_CODE

if [ -z "$AUTH_CODE" ]; then
    echo -e "  ${YELLOW}Skipping token exchange test. Run the app and use the web UI instead.${NC}"
else
    TOKEN_RESPONSE=$(curl -s -X POST "https://www.strava.com/oauth/token" \
        -d "client_id=${CLIENT_ID}" \
        -d "client_secret=${CLIENT_SECRET}" \
        -d "code=${AUTH_CODE}" \
        -d "grant_type=authorization_code")

    ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null || echo "")
    REFRESH_TOKEN=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('refresh_token',''))" 2>/dev/null || echo "")
    ATHLETE_ID=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('athlete',{}).get('id',''))" 2>/dev/null || echo "")
    EXPIRES_AT=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('expires_at',''))" 2>/dev/null || echo "")

    if [ -n "$ACCESS_TOKEN" ]; then
        pass "Token exchange successful"
        echo -e "    Athlete ID:  ${GREEN}$ATHLETE_ID${NC}"
        echo -e "    Token expiry: $(date -d @"$EXPIRES_AT" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$EXPIRES_AT")"

        # Save for subsequent tests
        SAVED_ACCESS_TOKEN="$ACCESS_TOKEN"
        SAVED_REFRESH_TOKEN="$REFRESH_TOKEN"
    else
        ERROR_MSG=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message','unknown error'))" 2>/dev/null || echo "unknown")
        fail "Token exchange failed" "$ERROR_MSG"
        echo ""
        echo "  Possible issues:"
        echo "  - Code is already used (each code is single-use)"
        echo "  - Wrong client_id or client_secret"
        echo "  - Redirect URI mismatch (must match what's configured in Strava API settings)"
        exit 1
    fi
fi

# ------------------------------------------------------------------
# Test token refresh
# ------------------------------------------------------------------
section "Token refresh test"

if [ -n "${SAVED_REFRESH_TOKEN:-}" ]; then
    REFRESH_RESPONSE=$(curl -s -X POST "https://www.strava.com/oauth/token" \
        -d "client_id=${CLIENT_ID}" \
        -d "client_secret=${CLIENT_SECRET}" \
        -d "grant_type=refresh_token" \
        -d "refresh_token=${SAVED_REFRESH_TOKEN}")

    NEW_ACCESS=$(echo "$REFRESH_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null || echo "")

    if [ -n "$NEW_ACCESS" ]; then
        pass "Token refresh successful"
        SAVED_ACCESS_TOKEN="$NEW_ACCESS"
    else
        ERROR_MSG=$(echo "$REFRESH_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message','unknown error'))" 2>/dev/null || echo "unknown")
        fail "Token refresh failed" "$ERROR_MSG"
    fi
else
    echo -e "  ${YELLOW}Skipping (no refresh token available)${NC}"
fi

# ------------------------------------------------------------------
# Test fetching activities
# ------------------------------------------------------------------
section "API endpoints test"

if [ -z "${SAVED_ACCESS_TOKEN:-}" ]; then
    echo -e "  ${YELLOW}No access token available. Skipping API tests.${NC}"
    echo "  Run the OAuth flow first to get a token."
else
    AUTH_HEADER="Authorization: Bearer ${SAVED_ACCESS_TOKEN}"

    # Test 1: Get athlete (minimal auth test)
    echo ""
    echo "  --- Test 1: Get athlete profile ---"
    ATHLETE_RESP=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTH_HEADER" "${STRAVA_BASE}/athlete")
    if [ "$ATHLETE_RESP" = "200" ]; then
        pass "GET /athlete - authenticated successfully"
    else
        fail "GET /athlete - returned HTTP $ATHLETE_RESP" "Token might be invalid"
    fi

    # Test 2: Get activities
    echo ""
    echo "  --- Test 2: Get activities (page 1, 5 items) ---"
    ACTIVITIES_RESP=$(curl -s -H "$AUTH_HEADER" "${STRAVA_BASE}/athlete/activities?page=1&per_page=5")
    HTTP_CODE=$(echo "$ACTIVITIES_RESP" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list):
        print(200)
    elif isinstance(data, dict) and 'message' in data:
        print(401)
    else:
        print(0)
except:
    print(0)
" 2>/dev/null || echo "0")

    if [ "$HTTP_CODE" = "200" ]; then
        COUNT=$(echo "$ACTIVITIES_RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
        pass "GET /athlete/activities - returned $COUNT activities"
        echo -e "    Last 5 activities:"

        echo "$ACTIVITIES_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for a in data:
    name = a.get('name', '?')
    date = a.get('start_date', '?')[:10]
    dist = a.get('distance', 0) / 1000
    typ = a.get('type', '?')
    hr = a.get('average_heartrate', '-')
    pace_seconds = 1000 / a.get('average_speed', 1) if a.get('average_speed', 0) > 0 else 0
    pace = f'{int(pace_seconds//60)}:{int(pace_seconds%60):02d}/km' if pace_seconds > 0 else '-'
    print(f'    - {date} {name:30s} {typ:10s} {dist:6.2f}km  pace {pace:10s}  HR {hr}')
" 2>/dev/null || true

        # Save first activity ID for next test
        FIRST_ACTIVITY_ID=$(echo "$ACTIVITIES_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if data:
    print(data[0].get('id', ''))
" 2>/dev/null || echo "")
    else
        ERROR=$(echo "$ACTIVITIES_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message','parse error'))" 2>/dev/null || echo "parse error")
        fail "GET /athlete/activities" "$ERROR"
    fi

    # Test 3: Get activity details
    echo ""
    echo "  --- Test 3: Get activity details ---"
    if [ -n "${FIRST_ACTIVITY_ID:-}" ]; then
        DETAIL_RESP=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTH_HEADER" "${STRAVA_BASE}/activities/${FIRST_ACTIVITY_ID}")
        if [ "$DETAIL_RESP" = "200" ]; then
            pass "GET /activities/{id} - details fetched"
            echo ""
            curl -s -H "$AUTH_HEADER" "${STRAVA_BASE}/activities/${FIRST_ACTIVITY_ID}" | python3 -c "
import sys, json
a = json.load(sys.stdin)
print(f'    Name:        {a.get(\"name\",\"?\")}')
print(f'    Date:        {str(a.get(\"start_date\",\"?\"))[:19]}')
print(f'    Distance:    {a.get(\"distance\",0)/1000:.2f} km')
print(f'    Time:        {a.get(\"moving_time\",0)//60} min')
print(f'    Avg HR:      {a.get(\"average_heartrate\",\"-\")}')
print(f'    Max HR:      {a.get(\"max_heartrate\",\"-\")}')
print(f'    Avg cadence: {a.get(\"average_cadence\",\"-\")}')
print(f'    Avg watts:   {a.get(\"average_watts\",\"-\")}')
print(f'    Elev gain:   {a.get(\"total_elevation_gain\",0)} m')
print(f'    Calories:    {a.get(\"calories\",\"-\")}')
print(f'    Gear:        {a.get(\"gear_id\",\"-\")}')
print(f'    Description: {str(a.get(\"description\",\"-\"))[:100]}')
" 2>/dev/null || true
        else
            fail "GET /activities/{id}" "HTTP $DETAIL_RESP"
        fi
    else
        echo -e "  ${YELLOW}  Skipping (no activity available)${NC}"
    fi

    # Test 4: Get streams
    echo ""
    echo "  --- Test 4: Get activity streams (GPS, HR, cadence per second) ---"
    if [ -n "${FIRST_ACTIVITY_ID:-}" ]; then
        STREAMS_RESP=$(curl -s -H "$AUTH_HEADER" \
            "${STRAVA_BASE}/activities/${FIRST_ACTIVITY_ID}/streams?keys=time,distance,heartrate,cadence,altitude,velocity_smooth&key_by_type=true")

        HAS_HR=$(echo "$STREAMS_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
hr = data.get('heartrate', {})
print('yes' if hr.get('data') else 'no')
" 2>/dev/null || echo "no")

        HAS_CADENCE=$(echo "$STREAMS_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
cad = data.get('cadence', {})
print('yes' if cad.get('data') else 'no')
" 2>/dev/null || echo "no")

        HAS_TIME=$(echo "$STREAMS_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
t = data.get('time', {})
print('yes' if t.get('data') else 'no')
" 2>/dev/null || echo "no")

        STREAM_OK=true
        if [ "$HAS_TIME" = "yes" ]; then
            pass "Stream: time data available"
        else
            fail "Stream: missing time data"
            STREAM_OK=false
        fi

        if [ "$HAS_HR" = "yes" ]; then
            HR_COUNT=$(echo "$STREAMS_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(len(data['heartrate']['data']))
" 2>/dev/null || echo "0")
            pass "Stream: heartrate data ($HR_COUNT data points)"
        else
            echo -e "  ${YELLOW}  Stream: heartrate data not available (no HR monitor?)${NC}"
        fi

        if [ "$HAS_CADENCE" = "yes" ]; then
            CAD_COUNT=$(echo "$STREAMS_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(len(data['cadence']['data']))
" 2>/dev/null || echo "0")
            pass "Stream: cadence data ($CAD_COUNT data points)"
        else
            echo -e "  ${YELLOW}  Stream: cadence data not available${NC}"
        fi

        # Show a sample of the stream data
        echo ""
        echo "  Sample data (first 5 seconds):"
        echo "$STREAMS_RESP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
time = data.get('time', {}).get('data', [])
hr = data.get('heartrate', {}).get('data', [])
cad = data.get('cadence', {}).get('data', [])
alt = data.get('altitude', {}).get('data', [])
vel = data.get('velocity_smooth', {}).get('data', [])
print(f'    {\"sec\":>4s}  {\"HR\":>3s}  {\"cad\":>3s}  {\"alt\":>5s}  {\"pace\":>8s}')
for i in range(min(5, len(time))):
    t = time[i] if i < len(time) else 0
    h = hr[i] if i < len(hr) else 0
    c = cad[i] if i < len(cad) else 0
    a = alt[i] if i < len(alt) else 0.0
    v = vel[i] if i < len(vel) else 0.0
    pace_s = 1000 / v if v > 0 else 0
    pace = f'{int(pace_s//60)}:{int(pace_s%60):02d}' if pace_s > 0 else '-'
    print(f'    {t:>4d}  {h:>3d}  {c:>3d}  {a:>5.0f}  {pace:>8s}')
" 2>/dev/null || echo "    (could not parse)"
    else
        echo -e "  ${YELLOW}  Skipping (no activity available)${NC}"
    fi
fi

# ------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------
section "Resultaten"
TOTAL=$((PASS + FAIL))
echo -e "  ${GREEN}${PASS} passed${NC} / ${RED}${FAIL} failed${NC} / $TOTAL total"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "  Some tests failed. Common issues:"
    echo "  - Invalid or expired token"
    echo "  - Strava API rate limiting (1000 requests per day per app)"
    echo "  - No activities in your account"
    echo "  - Activity is private (check Strava privacy settings)"
    echo ""
    echo "  Check the error messages above for details."
    exit 1
else
    echo ""
    echo -e "  ${GREEN}All tests passed! Strava API is working correctly.${NC}"
    echo ""
    echo "  Next steps:"
    echo "  1. Start the app: STRAVA_CLIENT_ID=xxx STRAVA_CLIENT_SECRET=xxx ./gradlew :runner:bootRun"
    echo "  2. Open http://localhost:8080"
    echo "  3. Click 'Strava koppelen' and authorize"
    echo "  4. Click 'Alle data ophalen'"
    exit 0
fi
