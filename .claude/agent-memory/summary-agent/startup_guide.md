---
name: startup_guide
description: Pulse 项目启动指南，包含环境配置、服务启动、验证步骤
type: reference
---

# Pulse Project Startup Guide

**Project:** Pulse Agent Community Platform
**Version:** 1.0
**Date:** 2026-03-31

---

## Quick Start (Docker)

The fastest way to run Pulse:

```bash
# 1. Clone the repository
git clone <repository-url>
cd pulse-community

# 2. Copy environment file
cp .env.example .env

# 3. Edit .env with your credentials
nano .env

# 4. Start all services
docker-compose up -d

# 5. Verify services
curl http://localhost:8080/api/health
curl http://localhost:8000/health
curl http://localhost:80

# 6. Open in browser
open http://localhost:80
```

---

## Prerequisites

### Required Software

| Software | Version | Purpose |
|----------|---------|---------|
| Java JDK | 17+ | Backend runtime |
| Node.js | 18+ | Frontend build |
| Python | 3.11+ | AI service runtime |
| PostgreSQL | 15+ | Database |
| Docker | 24+ | Container runtime |
| Docker Compose | 2+ | Multi-container orchestration |

### External Services

| Service | Purpose | Required |
|---------|---------|----------|
| OpenAI API | LLM integration | Yes |
| SMTP Server | Email notifications | Optional |

### Installation Commands

```bash
# macOS (with Homebrew)
brew install openjdk@17
brew install node@18
brew install python@3.11
brew install postgresql@15
brew install docker docker-compose

# Ubuntu/Debian
sudo apt install openjdk-17-jdk
sudo apt install nodejs npm
sudo apt install python3.11 python3-pip
sudo apt install postgresql-15
sudo apt install docker.io docker-compose

# Windows (with Chocolatey)
choco install openjdk17
choco install nodejs
choco install python311
choco install postgresql15
choco install docker-desktop
```

---

## Configuration

### Environment Variables

Create a `.env` file in the project root:

```bash
# ============================================
# Database Configuration
# ============================================
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=pulse
POSTGRES_USER=pulse
POSTGRES_PASSWORD=your_secure_password_here

# ============================================
# Backend Configuration (Spring Boot)
# ============================================
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=dev

# JWT Configuration
JWT_SECRET=your_jwt_secret_at_least_32_characters_long
JWT_EXPIRATION=86400000  # 24 hours in milliseconds
JWT_REFRESH_EXPIRATION=604800000  # 7 days in milliseconds

# Encryption (Jasypt)
JASYPT_ENCRYPTOR_PASSWORD=your_encryption_password_here

# OpenAI API Key
OPENAI_API_KEY=sk-your-openai-api-key-here

# AI Service URL
AI_SERVICE_URL=http://localhost:8000

# CORS Allowed Origins
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:80

# ============================================
# AI Service Configuration (Python)
# ============================================
AI_SERVICE_PORT=8000
AI_SERVICE_HOST=0.0.0.0

# OpenAI Configuration
OPENAI_MODEL=gpt-4
OPENAI_TIMEOUT=30
OPENAI_MAX_TOKENS=2000

# Injection Detection
INJECTION_DETECTION_ENABLED=true
MAX_CONTEXT_LENGTH=150

# ============================================
# Frontend Configuration (Vue 3)
# ============================================
VITE_API_BASE_URL=http://localhost:8080/api
VITE_WS_URL=ws://localhost:8080/ws
VITE_AI_SERVICE_URL=http://localhost:8000

# ============================================
# Monitoring (Optional)
# ============================================
ENABLE_METRICS=true
LOG_LEVEL=INFO
```

### Database Setup

```bash
# 1. Start PostgreSQL
# macOS
brew services start postgresql@15

# Ubuntu/Debian
sudo systemctl start postgresql

# Windows
# Use Services app to start PostgreSQL

# 2. Create database and user
psql -U postgres

CREATE DATABASE pulse;
CREATE USER pulse WITH PASSWORD 'your_secure_password_here';
GRANT ALL PRIVILEGES ON DATABASE pulse TO pulse;

# 3. Verify connection
psql -U pulse -d pulse -h localhost
```

---

## Manual Startup (Development)

### 1. Start Backend (Spring Boot)

```bash
# Navigate to backend directory
cd pulse-backend

# Build the project
./gradlew clean build

# Run database migrations (if using Flyway)
./gradlew flywayMigrate

# Start the application
./gradlew bootRun

# Or with specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Verify backend is running
curl http://localhost:8080/api/health
# Expected: {"status":"UP","timestamp":"2026-03-31T18:30:00Z"}
```

**Backend Startup Checklist:**
- [ ] Database connection established
- [ ] Migrations executed successfully
- [ ] JWT secret configured
- [ ] OpenAI API key set
- [ ] Health endpoint returns 200

### 2. Start AI Service (Python)

```bash
# Navigate to AI service directory
cd pulse-ai-side

# Create virtual environment
python -m venv venv

# Activate virtual environment
# macOS/Linux
source venv/bin/activate

# Windows
venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Copy environment file
cp .env.example .env

# Edit .env with your configuration
nano .env

# Start the service
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# Verify AI service is running
curl http://localhost:8000/health
# Expected: {"status":"healthy","service":"pulse-ai-side"}
```

**AI Service Startup Checklist:**
- [ ] Virtual environment activated
- [ ] Dependencies installed
- [ ] OpenAI API key configured
- [ ] Health endpoint returns 200
- [ ] Can reach backend API

### 3. Start Frontend (Vue 3)

```bash
# Navigate to frontend directory
cd pulse-frontend

# Install dependencies
npm install

# Copy environment file
cp .env.example .env

# Edit .env with your configuration
nano .env

# Start development server
npm run dev

# Or build for production
npm run build

# Verify frontend is running
curl http://localhost:5173
# Expected: HTML content

# Open in browser
open http://localhost:5173
```

**Frontend Startup Checklist:**
- [ ] Dependencies installed
- [ ] API base URL configured
- [ ] WebSocket URL configured
- [ ] Development server running
- [ ] Can reach backend API

---

## Docker Startup (Production)

### 1. Build Images

```bash
# Build all images
docker-compose build

# Or build individually
docker build -t pulse-backend:latest ./pulse-backend
docker build -t pulse-ai-side:latest ./pulse-ai-side
docker build -t pulse-frontend:latest ./pulse-frontend
```

### 2. Start Services

```bash
# Start all services in detached mode
docker-compose up -d

# View logs
docker-compose logs -f

# View logs for specific service
docker-compose logs -f pulse-backend

# Check service status
docker-compose ps

# Expected output:
# NAME                COMMAND                  SERVICE             STATUS              PORTS
# pulse-backend       "java -jar app.jar"      pulse-backend       running             0.0.0.0:8080->8080/tcp
# pulse-ai-side       "uvicorn app.main:a…"    pulse-ai-side       running             0.0.0.0:8000->8000/tcp
# pulse-frontend      "nginx -g 'daemon of…"   pulse-frontend      running             0.0.0.0:80->80/tcp
# postgres            "docker-entrypoint.s…"   postgres            running             0.0.0.0:5432->5432/tcp
```

### 3. Initialize Database

```bash
# Run migrations (if not automatic)
docker-compose exec pulse-backend ./gradlew flywayMigrate

# Or connect to database
docker-compose exec postgres psql -U pulse -d pulse

# Verify tables exist
\dt
# Expected: users, agents, agent_loops, etc.
```

### 4. Verify All Services

```bash
# Backend health check
curl http://localhost:8080/api/health
# Expected: {"status":"UP"}

# AI service health check
curl http://localhost:8000/health
# Expected: {"status":"healthy"}

# Frontend health check
curl http://localhost:80
# Expected: HTML content

# Database connection check
docker-compose exec postgres pg_isready -U pulse
# Expected: postgres:5432 - accepting connections
```

---

## First-Time Setup

### 1. Create Admin User

```bash
# Using curl
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "email": "admin@example.com",
    "password": "SecurePassword123!"
  }'

# Expected: {"success":true,"message":"User registered successfully"}
```

### 2. Login and Get Token

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "SecurePassword123!"
  }'

# Expected: {"token":"eyJhbGciOiJIUzI1NiIs...","refreshToken":"..."}

# Save token for future requests
export TOKEN="eyJhbGciOiJIUzI1NiIs..."
```

### 3. Create First Agent

```bash
# Create agent
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Research Assistant",
    "agentType": "RESEARCHER",
    "systemPrompt": "You are a helpful research assistant.",
    "apiKey": "sk-your-openai-key-here"
  }'

# Expected: {"id":1,"name":"Research Assistant","status":"ACTIVE"}
```

### 4. Trigger Agent Loop

```bash
# Manual trigger
curl -X POST http://localhost:8080/api/agents/1/trigger \
  -H "Authorization: Bearer $TOKEN"

# Expected: {"loopId":1,"status":"RUNNING"}

# Check status
curl http://localhost:8080/api/loops/1 \
  -H "Authorization: Bearer $TOKEN"

# Expected: {"id":1,"status":"COMPLETED","result":"..."}
```

---

## Troubleshooting

### Backend Issues

#### Database Connection Failed

```bash
# Check if PostgreSQL is running
docker-compose ps postgres

# Check database logs
docker-compose logs postgres

# Verify credentials
docker-compose exec postgres psql -U pulse -d pulse

# Common fixes:
# 1. Check .env file has correct credentials
# 2. Ensure database exists: CREATE DATABASE pulse;
# 3. Grant permissions: GRANT ALL PRIVILEGES ON DATABASE pulse TO pulse;
```

#### JWT Token Invalid

```bash
# Check JWT secret is set
echo $JWT_SECRET

# Verify token format
echo $TOKEN | cut -d'.' -f2 | base64 -d

# Common fixes:
# 1. Ensure JWT_SECRET is at least 32 characters
# 2. Regenerate token by logging in again
# 3. Check token hasn't expired
```

#### OpenAI API Error

```bash
# Test API key directly
curl https://api.openai.com/v1/models \
  -H "Authorization: Bearer $OPENAI_API_KEY"

# Common fixes:
# 1. Verify API key is valid
# 2. Check API key has credits
# 3. Ensure correct model access (gpt-4 vs gpt-3.5-turbo)
```

### AI Service Issues

#### Import Error

```bash
# Ensure virtual environment is activated
which python
# Should point to venv/bin/python

# Reinstall dependencies
pip install -r requirements.txt --force-reinstall
```

#### Timeout Errors

```python
# Check timeout setting in .env
OPENAI_TIMEOUT=30

# Increase timeout if needed
OPENAI_TIMEOUT=60

# Check network connectivity
curl -I https://api.openai.com
```

### Frontend Issues

#### API Connection Failed

```bash
# Check backend is running
curl http://localhost:8080/api/health

# Check CORS configuration
curl -I -X OPTIONS http://localhost:8080/api/agents \
  -H "Origin: http://localhost:5173"

# Common fixes:
# 1. Update VITE_API_BASE_URL in .env
# 2. Add frontend URL to CORS_ALLOWED_ORIGINS
# 3. Check browser console for specific errors
```

#### WebSocket Connection Failed

```javascript
// Check WebSocket URL in browser console
console.log(import.meta.env.VITE_WS_URL)

// Test WebSocket connection
const ws = new WebSocket('ws://localhost:8080/ws/loop-status')
ws.onopen = () => console.log('Connected')
ws.onerror = (err) => console.error('Error:', err)
```

---

## Health Check Endpoints

| Service | Endpoint | Expected Response |
|---------|----------|-------------------|
| Backend | GET /api/health | `{"status":"UP"}` |
| AI Service | GET /health | `{"status":"healthy"}` |
| Frontend | GET / | HTML content |
| Database | pg_isready | "accepting connections" |

---

## Useful Commands

### Docker

```bash
# Stop all services
docker-compose down

# Stop and remove volumes (clean slate)
docker-compose down -v

# View logs
docker-compose logs -f [service-name]

# Restart specific service
docker-compose restart pulse-backend

# Execute command in container
docker-compose exec pulse-backend bash

# Check resource usage
docker stats
```

### Database

```bash
# Connect to database
docker-compose exec postgres psql -U pulse -d pulse

# Backup database
docker-compose exec postgres pg_dump -U pulse pulse > backup.sql

# Restore database
cat backup.sql | docker-compose exec -T postgres psql -U pulse pulse

# View running queries
docker-compose exec postgres psql -U pulse -d pulse -c "SELECT * FROM pg_stat_activity"
```

### Logs

```bash
# Tail logs
docker-compose logs -f --tail=100 pulse-backend

# Search logs
docker-compose logs pulse-backend | grep "ERROR"

# Export logs
docker-compose logs --no-color > pulse.log
```

---

## Next Steps

After successful startup:

1. **Configure OpenAI API Key** - Add your API key in the Agent creation form
2. **Purchase Tokens** - Add tokens to your account for LLM calls
3. **Create Your First Agent** - Use the Agent Lab interface
4. **Trigger a Loop** - Test the complete workflow
5. **Monitor Status** - Watch real-time updates in the Monitor page

---

## Support

### Documentation
- API Documentation: http://localhost:8080/swagger-ui.html
- Architecture Guide: See `phase1_final_summary.md`
- Task List: See `phase2_tasks.md`

### Common Issues
- GitHub Issues: [project-repo]/issues
- Internal Wiki: [internal-wiki-url]

---

**Document Version:** 1.0
**Last Updated:** 2026-03-31 18:30
**Author:** Summary-Agent