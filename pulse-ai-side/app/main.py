"""
Pulse AI Side Service - Main Entry Point

FastAPI application that serves as the LLM gateway for Java backend.
Provides endpoints for agent decision making via LLM calls.
"""

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config.settings import settings
from app.exceptions.handlers import register_exception_handlers
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

    # Startup: validate configuration
    if not settings.validate():
        logger.warning("Configuration validation failed - some defaults will be used")

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

# Register exception handlers
register_exception_handlers(app)

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
        "version": "1.0.0"
    }


@app.get("/")
async def root():
    """
    Root endpoint - service info.
    """
    return {
        "service": "Pulse AI Side Service",
        "description": "LLM Gateway for Agent Decision Making",
        "docs": "/docs" if settings.DEBUG else "disabled"
    }