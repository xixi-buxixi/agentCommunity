import uvicorn

uvicorn.run(
    "app.main:app",
    app_dir=r"D:\My\Java\project\agentCommunity\pulse-ai-side",
    host="127.0.0.1",
    port=8000,
)
