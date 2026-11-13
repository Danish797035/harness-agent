import * as crypto from 'crypto';
import * as path from 'path';
import * as fs from 'fs';
import { promptCache } from './cache.js';
import { accountMutex } from '../browser/mutex.js';
import { browserManager } from '../browser/context.js';
import { processAndSaveImage } from '../media/pipeline.js';
import { cleanPrompt } from '../arena/prompt-cleaner.js';
import { getModelInfo } from '../model/allowlist.js';

export interface GenerationRequest {
  prompt: string;
  model?: string;
  n?: number;
  size?: string;
  accountId?: string;
}

export interface GenerationResult {
  created: number;
  data: Array<{
    url: string;
    revised_prompt?: string;
  }>;
  model: string;
  provider: 'harness';
}

const DEBUG_DIR = path.resolve(process.cwd(), process.env.DEBUG_DIR || './debug');
if (!fs.existsSync(DEBUG_DIR)) {
  fs.mkdirSync(DEBUG_DIR, { recursive: true });
}

export async function processGenerationJob(req: GenerationRequest): Promise<GenerationResult> {
  const modelInfo = getModelInfo(req.model);
  if (!modelInfo) {
    throw new Error(`Invalid model "${req.model}". Must be one of the allowed models.`);
  }

  const effectivePrompt = cleanPrompt(req.prompt);
  const size = req.size || '1024x1024';
  const cacheKey = promptCache.constructor ? (promptCache as any).constructor.createHash(effectivePrompt, modelInfo.id, size) : '';

  // 1. Check Cache
  const cached = promptCache.get(cacheKey);
  if (cached) {
    return {
      created: Math.floor(cached.createdAt / 1000),
      data: [{ url: cached.url, revised_prompt: effectivePrompt }],
      model: modelInfo.displayName,
      provider: 'harness',
    };
  }

  // 2. Lock Account
  const accountId = req.accountId || 'acct-1';
  const releaseLock = await accountMutex.acquire(accountId);
  const jobId = crypto.randomUUID();

  try {
    // 3. Obtain Pre-warmed Session
    const session = await browserManager.getOrInitSession(accountId);

    // 4. Select Model
    await session.arenaPage.selectModel(modelInfo.displayName);

    // 5. Submit Cleaned Prompt
    await session.arenaPage.submitPrompt(effectivePrompt);

    // 6. Await Result
    const { imageSrc } = await session.arenaPage.awaitImage(120000);

    // 7. Download & Save Media
    const media = await processAndSaveImage(session.prewarmedPage, imageSrc);

    // 8. Update Cache
    promptCache.set(cacheKey, media.url, modelInfo.id);

    // Cooldown jitter: 8-20s on success
    const jitterMs = Math.floor(Math.random() * (20000 - 8000 + 1)) + 8000;
    accountMutex.setCooldown(accountId, jitterMs);

    return {
      created: Math.floor(Date.now() / 1000),
      data: [{ url: media.url, revised_prompt: effectivePrompt }],
      model: modelInfo.displayName,
      provider: 'harness',
    };
  } catch (err: any) {
    // Apply failure cooldown: 90s
    accountMutex.setCooldown(accountId, 90000);

    // Debug dump
    try {
      const dumpFolder = path.join(DEBUG_DIR, jobId);
      fs.mkdirSync(dumpFolder, { recursive: true });

      const session = await browserManager.getOrInitSession(accountId).catch(() => null);
      if (session && !session.prewarmedPage.isClosed()) {
        await session.prewarmedPage.screenshot({ path: path.join(dumpFolder, 'screenshot.png'), fullPage: true }).catch(() => {});
        const html = await session.prewarmedPage.content().catch(() => '');
        fs.writeFileSync(path.join(dumpFolder, 'page.html'), html);
      }
      fs.writeFileSync(path.join(dumpFolder, 'error.log'), `${new Date().toISOString()}: ${err?.message || err}\n${err?.stack || ''}`);
    } catch {}

    throw err;
  } finally {
    releaseLock();
  }
}
