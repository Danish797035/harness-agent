FROM node:22-bullseye-slim

# Install system dependencies for Chromium and Xvfb virtual display
RUN apt-get update && apt-get install -y \
    xvfb \
    xauth \
    chromium \
    fonts-liberation \
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libpango-1.0-0 \
    libcairo2 \
    libasound2 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install pnpm
RUN corepack enable && corepack prepare pnpm@latest --activate

WORKDIR /app

# Copy package descriptors and install dependencies
COPY package.json pnpm-lock.yaml* ./
RUN pnpm install

# Copy source code and build
COPY . .
RUN pnpm run build

# Directories for profiles, media and debug dumps
RUN mkdir -p profiles media debug tmp

ENV DISPLAY=:99
ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0

EXPOSE 3000

# Start Xvfb virtual display and node microservice
CMD ["sh", "-c", "Xvfb :99 -screen 0 1366x768x24 & node dist/src/index.js"]
