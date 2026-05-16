#!/bin/bash
# ============================================
# Pulse - Manual Deploy Script
# Usage: bash deploy.sh [all|frontend|backend|aiside]
# ============================================
set -e

DEPLOY_TARGET="${1:-all}"
REPO_DIR="/opt/pulse/repo"
REPO_URL="https://github.com/xixi-buxixi/agentCommunity.git"
BRANCH="main"

log() { echo ">>> $(date '+%H:%M:%S') $1"; }

# ---------- clone / pull ----------
ensure_repo() {
    if [ -d "$REPO_DIR/.git" ]; then
        log "Pulling latest code..."
        cd "$REPO_DIR"
        git fetch origin "$BRANCH"
        git reset --hard "origin/$BRANCH"
    else
        log "Cloning repository..."
        mkdir -p "$(dirname "$REPO_DIR")"
        git clone -b "$BRANCH" "$REPO_URL" "$REPO_DIR"
    fi
}

# ---------- frontend ----------
deploy_frontend() {
    log "=== Deploying Frontend ==="
    ensure_repo
    cd "$REPO_DIR/pulse-frontend"

    if ! command -v npm &>/dev/null; then
        log "Node.js not installed, installing..."
        curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
        apt-get install -y nodejs
    fi

    npm ci --production=false
    npm run build

    log "Deploying to /var/www/pulse/"
    rm -rf /var/www/pulse/assets /var/www/pulse/index.html 2>/dev/null || true
    mkdir -p /var/www/pulse
    cp -r dist/* /var/www/pulse/
    chmod -R 755 /var/www/pulse/

    # Setup nginx SPA config if not exists
    if command -v nginx &>/dev/null; then
        if [ ! -f /etc/nginx/sites-enabled/pulse ]; then
            log "Setting up nginx SPA config..."
            cp "$REPO_DIR/deploy/nginx-pulse.conf" /etc/nginx/sites-available/pulse
            sed -i "s/QINIUYUN_IP/your-qiniuyun-server-ip/g" /etc/nginx/sites-available/pulse
            ln -sf /etc/nginx/sites-available/pulse /etc/nginx/sites-enabled/pulse
            rm -f /etc/nginx/sites-enabled/default
            nginx -t && systemctl reload nginx
            log "Nginx configured OK"
        else
            log "Nginx already configured, skipping"
        fi
    fi
    log "Frontend deployed OK"
}

# ---------- backend ----------
deploy_backend() {
    log "=== Deploying Backend ==="
    ensure_repo
    cd "$REPO_DIR/pulse-backend"

    if ! command -v java &>/dev/null; then
        log "Java not installed!"
        exit 1
    fi
    if ! command -v mvn &>/dev/null && ! [ -x ./mvnw ]; then
        log "Maven not installed!"
        exit 1
    fi

    MAVEN_CMD="mvn"
    [ -x ./mvnw ] && MAVEN_CMD="./mvnw"

    $MAVEN_CMD clean package -DskipTests -B -q

    # Ensure .env exists
    if [ ! -f /opt/pulse/backend/.env ] && [ -f "$REPO_DIR/deploy/backend/.env.example" ]; then
        cp "$REPO_DIR/deploy/backend/.env.example" /opt/pulse/backend/.env
        log "Created .env from example - please edit DB credentials!"
    fi

    pkill -f 'pulse-backend.*\.jar' 2>/dev/null || true
    sleep 3
    mkdir -p /opt/pulse/logs /opt/pulse/backend

    set -a && source /opt/pulse/backend/.env && set +a

    nohup java -Xms512m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
        -jar "$REPO_DIR/pulse-backend/target/pulse-backend-1.0.0-SNAPSHOT.jar" \
        --spring.profiles.active=prod \
        > /opt/pulse/logs/backend.log 2>&1 &

    log "Backend restarted, PID: $!"
}

# ---------- ai-side ----------
deploy_aiside() {
    log "=== Deploying AI Side ==="
    ensure_repo
    cd "$REPO_DIR/pulse-ai-side"

    if [ -f /opt/pulse/ai-side/venv/bin/activate ]; then
        source /opt/pulse/ai-side/venv/bin/activate
        pip install -r requirements.txt -q 2>/dev/null || true
    fi

    pkill -f 'uvicorn.*app.main:app' 2>/dev/null || true
    sleep 2
    mkdir -p /opt/pulse/logs

    cd /opt/pulse/ai-side
    source venv/bin/activate 2>/dev/null || true
    nohup uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1 \
        --log-level info > /opt/pulse/logs/ai-side.log 2>&1 &

    log "AI Side restarted, PID: $!"
}

# ---------- main ----------
case "$DEPLOY_TARGET" in
    frontend)
        deploy_frontend
        ;;
    backend)
        deploy_backend
        ;;
    aiside)
        deploy_aiside
        ;;
    all)
        deploy_frontend
        deploy_backend
        deploy_aiside
        ;;
    *)
        echo "Usage: bash deploy.sh [all|frontend|backend|aiside]"
        exit 1
        ;;
esac

log "=== Deploy Complete ==="
