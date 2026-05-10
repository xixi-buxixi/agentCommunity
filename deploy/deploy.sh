#!/bin/bash
set -e

# ============================================================
# Pulse Platform Deployment Script
# Target: 2C4G Ubuntu Server
# ============================================================

APP_DIR=/opt/pulse
FRONTEND_DIR=${APP_DIR}/frontend
BACKEND_DIR=${APP_DIR}/backend
AI_SIDE_DIR=${APP_DIR}/ai-side
LOG_DIR=${APP_DIR}/logs

echo "============================================"
echo " Pulse Platform Deployment"
echo " Target: 2C4G Ubuntu 22.04+"
echo "============================================"

# ---- Step 1: System Dependencies ----
echo ""
echo "[1/6] Installing system dependencies..."
apt-get update -qq
apt-get install -y -qq openjdk-21-jdk-headless nginx mysql-server redis-server python3-venv python3-pip curl unzip

# ---- Step 2: Directory Layout ----
echo ""
echo "[2/6] Creating directory layout..."
mkdir -p ${FRONTEND_DIR} ${BACKEND_DIR} ${AI_SIDE_DIR} ${LOG_DIR}

# ---- Step 3: MySQL Setup ----
echo ""
echo "[3/6] Configuring MySQL..."

# Low-memory MySQL config
cat > /etc/mysql/mysql.conf.d/99-pulse.cnf << 'MYSQL'
[mysqld]
innodb_buffer_pool_size = 256M
max_connections = 40
performance_schema = OFF
table_open_cache = 400
innodb_log_file_size = 64M
MYSQL

systemctl restart mysql || service mysql restart

# Wait for MySQL to be ready
for i in $(seq 1 20); do
    if mysqladmin ping -u root --silent 2>/dev/null; then
        break
    fi
    sleep 1
done

# Run schema
echo "Creating database and tables..."
mysql -u root < ${BACKEND_DIR}/schema.sql 2>/dev/null || {
    # If schema fails (e.g. db already exists), try force
    mysql -u root -e "CREATE DATABASE IF NOT EXISTS pulse_db DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;" 2>/dev/null
    mysql -u root pulse_db < ${BACKEND_DIR}/schema.sql 2>/dev/null || true
}

echo "MySQL configured."

# ---- Step 4: Redis Setup ----
echo ""
echo "[4/6] Configuring Redis..."

cat > /etc/redis/redis.conf.d/pulse.conf << 'REDIS'
maxmemory 128mb
maxmemory-policy allkeys-lru
REDIS

systemctl restart redis-server || service redis-server restart
echo "Redis configured."

# ---- Step 5: Nginx Setup ----
echo ""
echo "[5/6] Configuring Nginx..."

cat > /etc/nginx/sites-available/pulse << 'NGINX'
server {
    listen 80;
    server_name _;

    # Frontend static files
    root /opt/pulse/frontend;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml text/javascript;
    gzip_min_length 256;

    # API reverse proxy to Spring Boot
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    # Frontend SPA - all other routes to index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Static asset caching
    location /assets/ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }
}
NGINX

# Remove default site and enable pulse
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/pulse /etc/nginx/sites-enabled/pulse

# Test and reload nginx
nginx -t && systemctl reload nginx || service nginx reload
echo "Nginx configured."

# ---- Step 6: Python AI Side Setup ----
echo ""
echo "[6/6] Setting up Python AI Side..."

cd ${AI_SIDE_DIR}
python3 -m venv venv
source venv/bin/activate
pip install -q -r requirements.txt
deactivate

echo ""
echo "============================================"
echo " Deployment files are in place!"
echo "============================================"
echo ""
echo "Next steps (run manually):"
echo ""
echo "1. Configure environment variables:"
echo "   cp ${BACKEND_DIR}/.env.example ${BACKEND_DIR}/.env"
echo "   vim ${BACKEND_DIR}/.env"
echo ""
echo "2. Start services:"
echo "   ${APP_DIR}/start-all.sh"
echo ""
echo "3. Check status:"
echo "   ${APP_DIR}/status.sh"
