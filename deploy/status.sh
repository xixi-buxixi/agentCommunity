#!/bin/bash

APP_DIR=/opt/pulse
LOG_DIR=${APP_DIR}/logs

echo "============================================"
echo " Pulse Platform Status"
echo " $(date)"
echo "============================================"
echo ""

# Memory overview
echo "--- Memory Usage ---"
free -h
echo ""

# Process check
echo "--- Services ---"
for svc in "nginx" "java" "uvicorn" "mysqld" "redis-server"; do
    pid=$(pgrep -f "$svc" | head -1)
    if [ -n "$pid" ]; then
        mem=$(ps -o rss= -p "$pid" 2>/dev/null | awk '{printf "%.0f MB", $1/1024}')
        echo "  [OK]  $svc (PID: $pid, RSS: ${mem:-N/A})"
    else
        echo "  [DOWN] $svc"
    fi
done
echo ""

# Port check
echo "--- Ports ---"
for port in 80 8080 8000 3306 6379; do
    if ss -tlnp | grep -q ":${port} "; then
        echo "  [LISTEN] :${port}"
    else
        echo "  [CLOSED] :${port}"
    fi
done
echo ""

# Health endpoints
echo "--- Health ---"
echo -n "  Nginx:     "; curl -s -o /dev/null -w '%{http_code}' http://localhost:80/ 2>/dev/null || echo "N/A"
echo -n "  API:       "; curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/ 2>/dev/null || echo "N/A"
echo -n "  AI Gateway: "; curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/ 2>/dev/null || echo "N/A"
echo ""

# Recent log tails
echo "--- Recent Logs (last 5 lines each) ---"
for logfile in ${LOG_DIR}/*.log; do
    if [ -f "$logfile" ]; then
        echo "  [${logfile##*/}]"
        tail -3 "$logfile" 2>/dev/null | sed 's/^/    /'
        echo ""
    fi
done
