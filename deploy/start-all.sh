#!/bin/bash
set -e

APP_DIR=/opt/pulse
LOG_DIR=${APP_DIR}/logs

echo "============================================"
echo " Starting Pulse Platform Services"
echo " Memory target: ~2.1G / 4G"
echo "============================================"

# ---- Load env vars ----
if [ -f ${APP_DIR}/backend/.env ]; then
    export $(grep -v '^#' ${APP_DIR}/backend/.env | xargs)
fi

# ---- Python AI Side (FastAPI + Uvicorn) ----
echo "[1/3] Starting Python AI Gateway..."
cd ${APP_DIR}/ai-side
source venv/bin/activate
nohup uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1 \
    --log-level info > ${LOG_DIR}/ai-side.log 2>&1 &
echo "  PID: $! | Port: 8000 | Workers: 1"

# ---- Spring Boot Backend ----
echo "[2/3] Starting Spring Boot Backend..."
java -Xms512m -Xmx768m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -jar ${APP_DIR}/backend/pulse-backend-1.0.0-SNAPSHOT.jar \
    --spring.profiles.active=prod \
    > ${LOG_DIR}/backend.log 2>&1 &
echo "  PID: $! | Port: 8080 | Heap: 512m-768m"

# ---- Nginx (should already be running) ----
echo "[3/3] Ensuring Nginx is running..."
systemctl reload nginx 2>/dev/null || service nginx reload 2>/dev/null || true

echo ""
echo "============================================"
echo " All services started!"
echo "============================================"
echo ""
echo " Service      | Port | Status"
echo " -------------|------|-------"
echo " Nginx        | 80   | $(curl -s -o /dev/null -w '%{http_code}' http://localhost:80/ 2>/dev/null || echo 'checking...')"
echo " Spring Boot  | 8080 | starting (wait ~30s)"
echo " Python AI    | 8000 | starting"
echo ""
echo " Check logs: tail -f ${LOG_DIR}/*.log"
echo " Check status: ${APP_DIR}/status.sh"
