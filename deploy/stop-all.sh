#!/bin/bash

echo "Stopping Pulse Platform..."

# Stop in reverse order
for proc in "uvicorn" "java" "nginx"; do
    pid=$(pgrep -f "$proc" | head -1)
    if [ -n "$pid" ]; then
        echo "  Stopping $proc (PID: $pid)..."
        kill "$pid" 2>/dev/null || true
        sleep 1
    fi
done

echo "All services stopped."
