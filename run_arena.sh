#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_DIR=""

if [ -d "$DIR/tools/harness-image" ]; then
    HARNESS_DIR="$DIR/tools/harness-image"
elif [ -d "$DIR/../harness-image" ]; then
    HARNESS_DIR="$DIR/../harness-image"
fi

if [ -z "$HARNESS_DIR" ]; then
    echo "❌ Error: harness-image directory not found."
    exit 1
fi

echo "🚀 Starting LM Arena Image Generation Service on port 3000..."
echo "📂 Working directory: $HARNESS_DIR"

if lsof -i :3000 >/dev/null 2>&1; then
    echo "⚠️ Port 3000 is already in use. Service may already be running."
    echo "💡 Test connection: curl http://127.0.0.1:3000/v1/models"
    exit 0
fi

cd "$HARNESS_DIR"

export PORT=3000
export HEADLESS=${HEADLESS:-true}
export ARENA_URL=${ARENA_URL:-"https://lmarena.ai/?arena=image"}

if [ -f "dist/index.js" ]; then
    echo "▶️ Running node dist/index.js..."
    exec node dist/index.js
elif command -v pnpm >/dev/null 2>&1; then
    echo "▶️ Running pnpm start..."
    exec pnpm start
else
    echo "▶️ Running node src/index.ts via tsx..."
    exec npx -y tsx src/index.ts
fi
