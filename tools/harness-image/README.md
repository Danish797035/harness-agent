# harness-image

A standalone Node.js/TypeScript microservice acting as the image generation backend for the **Harness** coding agent, powered by automated persistent browser sessions on LM Arena Direct Chat.

---

## 🌟 Key Features

1. **Silent Background Execution**: Runs completely silently behind virtual displays (`Xvfb`) or headless mode. No intrusive popups.
2. **Instant User Chat UX**: Emits `"Generating your image... 🎨"` immediately upon user request, followed by provenance output:
   ```text
   model: Seedream 5.0 Lite
   generated on: Harness
   ```
3. **Pre-warmed Background Tab**: Keeps an active Direct Chat tab initialized in the background, slashing cold-start generation time from 45s down to 10–15s.
4. **Default Model**: Configured with `seedream 5.0 lite` by default.
5. **Smart Prompt Extractor**: Automatically cleans conversational phrases:
   - Input: `"generate a image for me a dog in delhi park"`
   - Sent to Arena: `"a dog in delhi park"`
6. **OpenAI-Compatible API**: Seamless drop-in replacement (`/v1/images/generations`, `/v1/models`).
7. **Prompt Hash Cache**: SQLite prompt-hash caching (7-day TTL) using Node 22 native `node:sqlite`.
8. **Single-Writer Mutex**: Strict per-account concurrency lock—never multiple tabs per account.
9. **Media Storage**: Authenticated cookie-based byte downloads, EXIF metadata stripping, local `.webp` storage.

---

## 📁 Repository Structure

```
├── Dockerfile              # Node 22 + Chromium + Xvfb container
├── package.json            # pnpm package definitions
├── tsconfig.json           # Strict TypeScript configuration
├── .env.example            # Environment variables template
├── scripts/
│   ├── spike-test.ts       # Phase 0 throwaway validation & selector probe
│   └── test-cleaner.ts     # Unit tests for prompt cleaning logic
├── src/
│   ├── index.ts            # Fastify server entrypoint & background pre-warming
│   ├── api/
│   │   └── routes.ts       # OpenAI-compatible API routes & health check
│   ├── arena/
│   │   ├── page-object.ts  # Direct Chat Page Object (select, type, await)
│   │   ├── prompt-cleaner.ts # Conversational boilerplate stripping
│   │   └── selectors.ts    # Centralized selectors & SELECTOR_DRIFT watchdog
│   ├── browser/
│   │   ├── context.ts      # Persistent stealth profile & pre-warmed session manager
│   │   └── mutex.ts        # Account mutex & cooldown jitter tracker
│   ├── media/
│   │   └── pipeline.ts     # Cookie download, EXIF strip & webp storage
│   ├── model/
│   │   └── allowlist.ts    # Allowlist & display names
│   └── queue/
│       ├── cache.ts        # Built-in SQLite cache (7-day TTL)
│       └── worker.ts       # End-to-end generation job worker
└── test/
    └── smoke.spec.ts       # API & validation smoke test suite
```

---

## 🚀 Getting Started

### 1. Environment Setup

Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```

Configure your credentials:
```env
PORT=3000
DEFAULT_MODEL=seedream-5.0-lite
GOOGLE_EMAIL=your-harness-account@gmail.com
GOOGLE_PASSWORD=your-secure-password
HEADLESS=true
```

### 2. Manual First Login

To establish the persistent profile without tripping automated captchas:
```bash
pnpm run spike
```
Log in manually if prompted by Google/Cloudflare. Once completed, the session is saved in `./tmp/profile` or `./profiles/acct-1` and will survive restarts.

### 3. Running the Microservice

```bash
# Development (with auto-reload)
pnpm run dev

# Production
pnpm run build
pnpm start
```

---

## 🐳 Docker Deployment

The provided Dockerfile packages Node 22, Chromium, and Xvfb:

```bash
docker build -t harness-image .
docker run -d -p 3000:3000 -v $(pwd)/profiles:/app/profiles --name harness-image harness-image
```

---

## 📡 API Reference

### 1. Generate Image
```bash
POST /v1/images/generations
Content-Type: application/json

{
  "prompt": "generate a image for me a dog in delhi park",
  "model": "seedream-5.0-lite"
}
```

Response:
```json
{
  "created": 1731486120,
  "data": [
    {
      "url": "http://localhost:3000/media/7b7f1418-4f24-4f99-9b9a-131f47c32b50.webp",
      "revised_prompt": "a dog in delhi park"
    }
  ],
  "model": "Seedream 5.0 Lite",
  "provider": "harness"
}
```

### 2. List Models
```bash
GET /v1/models
```

### 3. Service Health
```bash
GET /health
```

---

## ⚠️ Legal & Maintenance Notice

- **Unofficial Integration**: This service interfaces with LM Arena via automated browser sessions. LM Arena reserves the right to modify DOM structures, update bot detection mechanisms, or adjust their Terms of Service at any time.
- **Selector Drift Watchdog**: When selectors fail 3 consecutive times, a structured `SELECTOR_DRIFT` warning is emitted to alert developers of upstream UI updates.
