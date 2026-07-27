"""
Pulse AI Side Service - Main Entry Point

FastAPI application that serves as the LLM gateway for Java backend.
Provides endpoints for agent decision making via LLM calls.

Enhanced features:
- Authentication middleware (service-to-service auth)
- Rate limiting (production-ready)
- Improved error handling with proper HTTP status codes
"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config.settings import settings
from app.exceptions.handlers import register_exception_handlers
from app.middleware.auth import AuthMiddleware, RateLimitConfig, RateLimiter
from app.routers.llm import router as llm_router

# Configure logging
logging.basicConfig(
    level=logging.INFO if not settings.DEBUG else logging.DEBUG,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Application lifespan handler.
    Startup and shutdown logic.
    """
    logger.info("Pulse AI Side Service starting up...")
    logger.info(f"Debug mode: {settings.DEBUG}")
    logger.info(f"Timeout: {settings.REQUEST_TIMEOUT_SECONDS}s")
    logger.info(f"Rate limits: {settings.RATE_LIMIT_REQUESTS_PER_MINUTE}/min, "
                f"{settings.RATE_LIMIT_REQUESTS_PER_HOUR}/hour")

    # Settings validate themselves during construction and refuse to build without
    # a SERVICE_TOKEN outside DEBUG, so reaching this point means auth is armed.
    logger.info(
        "Service auth: %s | upstream budget: %.1fs (must stay under the caller timeout)",
        "enabled" if not settings.DEBUG else "DISABLED (DEBUG)",
        settings.total_request_budget_seconds,
    )

    yield

    logger.info("Pulse AI Side Service shutting down...")


# Create FastAPI application
app = FastAPI(
    title="Pulse AI Side Service",
    description="LLM Gateway for Pulse Agent Community System",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs" if settings.DEBUG else None,
    redoc_url="/redoc" if settings.DEBUG else None,
)

# Register exception handlers (improved with proper status codes)
register_exception_handlers(app)

# Add authentication and rate limiting middleware
# Configure rate limiter with settings
rate_limit_config = RateLimitConfig(
    requests_per_minute=settings.RATE_LIMIT_REQUESTS_PER_MINUTE,
    requests_per_hour=settings.RATE_LIMIT_REQUESTS_PER_HOUR,
    burst_limit=settings.RATE_LIMIT_BURST,
)
rate_limiter = RateLimiter(rate_limit_config)

# Add middleware - order matters: auth/rate limit before CORS
app.add_middleware(
    AuthMiddleware,
    service_token=settings.SERVICE_TOKEN,
    rate_limiter=rate_limiter,
)

# CORS middleware (for Java backend communication)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Java backend can call from any origin
    allow_credentials=True,
    allow_methods=["POST", "GET"],
    allow_headers=["*"],
)

# Include routers
app.include_router(llm_router, prefix="/v1/llm", tags=["LLM"])


@app.get("/health")
async def health_check():
    """
    Health check endpoint for service monitoring.
    """
    return {
        "status": "healthy",
        "service": "pulse-ai-side",
        "version": "1.0.0",
        "rate_limits": {
            "per_minute": settings.RATE_LIMIT_REQUESTS_PER_MINUTE,
            "per_hour": settings.RATE_LIMIT_REQUESTS_PER_HOUR,
        },
        # Reflects the actual middleware behaviour: DEBUG is the only way auth is off
        "auth_enabled": not settings.DEBUG,
    }


@app.get("/")
async def root():
    """
    Root endpoint - service info.
    """
    return {
        "service": "Pulse AI Side Service",
        "description": "LLM Gateway for Agent Decision Making",
        "docs": "/docs" if settings.DEBUG else "disabled",
        "security": {
            "auth_required": not settings.DEBUG,
            "rate_limiting": True,
        }
    }