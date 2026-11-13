import { chromium, type BrowserContext, type Page } from 'playwright';
import * as fs from 'fs';
import * as path from 'path';

interface TimingRecord {
  start: number;
  loginReuse?: number;
  pageLoad?: number;
  promptSubmit?: number;
  imageReady?: number;
  download?: number;
  total?: number;
}

interface RunSummary {
  sessionReused: 'yes' | 'no';
  challengeDetected: 'yes' | 'no';
  modelSelector: string;
  submitSelector: string;
  imageContainerSelector: string;
  cleanedPrompt: string;
  totalTimeSec: number;
  fileSizeBytes: number;
}

const PROFILE_DIR = path.resolve(process.cwd(), 'tmp/profile');
const TMP_DIR = path.resolve(process.cwd(), 'tmp');
const OUT_IMAGE = path.resolve(TMP_DIR, 'out.webp');
const ARENA_URL = process.env.ARENA_URL || 'https://lmarena.ai/?arena=image';
const RAW_USER_PROMPT = process.env.TEST_PROMPT || 'generate a image for me a dog in delhi park';
const TARGET_MODEL = process.env.TARGET_MODEL || 'seedream 5.0 lite';
const GOOGLE_EMAIL = process.env.GOOGLE_EMAIL || '';
const GOOGLE_PASSWORD = process.env.GOOGLE_PASSWORD || '';

/**
 * Strips conversational conversational fluff so only the real image prompt is sent.
 * Example: "generate a image for me a dog in delhi park" -> "a dog in delhi park"
 */
export function cleanPrompt(raw: string): string {
  let cleaned = raw.trim();

  const patterns = [
    /^(please\s+)?(can\s+you\s+)?(generate|create|make|draw|show|render|give\s+me)(\s+a|\s+an|\s+the)?\s+(image|picture|photo|illustration)(\s+for\s+me|\s+to\s+me)?(\s+of)?\s*/i,
    /^(generate|create|make|draw)\s+(a\s+picture|an\s+image|a\s+photo)\s*(for\s+me)?\s*(of)?\s*/i,
    /^(i\s+want\s+an\s+image\s+of|give\s+me\s+an\s+image\s+of|i\s+need\s+a\s+picture\s+of)\s*/i,
    /^(draw|paint)\s+(me\s+)?(a\s+picture\s+of\s+)?/i,
  ];

  for (const regex of patterns) {
    if (regex.test(cleaned)) {
      cleaned = cleaned.replace(regex, '').trim();
      break;
    }
  }

  // Fallback: if user starts with "a image for me ..." or "for me ..."
  cleaned = cleaned.replace(/^(for\s+me\s+)/i, '').trim();

  return cleaned || raw;
}

async function handleGoogleLogin(page: Page, email: string, pass: string): Promise<boolean> {
  if (!email || !pass) {
    console.log('[Login] No GOOGLE_EMAIL / GOOGLE_PASSWORD provided in settings. Skipping automated login.');
    return false;
  }

  try {
    const isGoogleAuth = page.url().includes('accounts.google.com');
    if (!isGoogleAuth) return false;

    console.log('[Login] Google login page detected. Attempting automated credential fill...');
    const emailInput = page.locator('input[type="email"]');
    if ((await emailInput.count()) > 0) {
      await emailInput.fill(email);
      await page.keyboard.press('Enter');
      await page.waitForTimeout(3000);
    }

    const passwordInput = page.locator('input[type="password"]');
    if ((await passwordInput.count()) > 0) {
      await passwordInput.fill(pass);
      await page.keyboard.press('Enter');
      await page.waitForTimeout(5000);
    }

    // Check for 2FA / TOTP prompt
    const twoFactorLoc = page.locator('text="2-Step Verification"');
    if ((await twoFactorLoc.count()) > 0) {
      console.log('⚠️ [Login ALERT] 2FA / TOTP prompt encountered! Marked as needs_human.');
      return false;
    }

    console.log('[Login] Credentials submitted successfully.');
    return true;
  } catch (err: any) {
    console.log(`[Login] Automated login error: ${err?.message}`);
    return false;
  }
}

async function main() {
  const timings: TimingRecord = { start: Date.now() };
  const effectivePrompt = cleanPrompt(RAW_USER_PROMPT);

  const summary: RunSummary = {
    sessionReused: 'no',
    challengeDetected: 'no',
    modelSelector: 'not_found',
    submitSelector: 'not_found',
    imageContainerSelector: 'not_found',
    cleanedPrompt: effectivePrompt,
    totalTimeSec: 0,
    fileSizeBytes: 0,
  };

  if (!fs.existsSync(TMP_DIR)) {
    fs.mkdirSync(TMP_DIR, { recursive: true });
  }

  console.log('====================================================');
  console.log('🚀 HARNESS-IMAGE: Silent Background Pipeline');
  console.log('====================================================');
  console.log(`[Input] Raw User Message: "${RAW_USER_PROMPT}"`);
  console.log(`[Input] Cleaned Prompt:   "${effectivePrompt}"`);
  console.log(`[Config] Default Model:    ${TARGET_MODEL}`);
  console.log(`[Config] Profile dir:      ${PROFILE_DIR}`);
  console.log('----------------------------------------------------');

  // Immediately notify UI/User
  console.log('\n💬 [Chat UI Display] "Generating your image... 🎨"\n');

  const profileExisted = fs.existsSync(PROFILE_DIR) && fs.readdirSync(PROFILE_DIR).length > 0;
  let context: BrowserContext | null = null;
  let page: Page | null = null;

  try {
    const launchStart = Date.now();
    console.log('[Background] Initializing stealth browser context silently...');

    context = await chromium.launchPersistentContext(PROFILE_DIR, {
      headless: process.env.HEADLESS === 'true' || false,
      viewport: { width: 1366, height: 768 },
      userAgent:
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
      args: [
        '--disable-blink-features=AutomationControlled',
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-infobars',
        '--disable-dev-shm-usage',
        '--window-size=1366,768',
      ],
      ignoreDefaultArgs: ['--enable-automation'],
    });

    const initialCookies = await context.cookies();
    const hasAuthCookie = initialCookies.some(
      (c) =>
        c.name.toLowerCase().includes('session') ||
        c.name.toLowerCase().includes('auth') ||
        c.name.toLowerCase().includes('token') ||
        c.domain.includes('google') ||
        c.domain.includes('lmarena')
    );
    if (profileExisted && hasAuthCookie) {
      summary.sessionReused = 'yes';
    }
    timings.loginReuse = Date.now() - launchStart;
    console.log(`[Timing] Session reuse check: ${summary.sessionReused} (${timings.loginReuse}ms)`);

    page = context.pages()[0] || (await context.newPage());
    page.setDefaultTimeout(120000);

    // Step 2: Open or reuse pre-warmed Direct Chat tab
    const pageLoadStart = Date.now();
    console.log(`[Background] Connecting to Arena Direct Chat (${ARENA_URL})...`);
    await page.goto(ARENA_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    timings.pageLoad = Date.now() - pageLoadStart;

    // Check if redirected to Google Login
    if (page.url().includes('accounts.google.com')) {
      await handleGoogleLogin(page, GOOGLE_EMAIL, GOOGLE_PASSWORD);
      await page.waitForURL((url) => !url.toString().includes('accounts.google.com'), { timeout: 30000 });
    }

    // Step 3: Check for Cloudflare / Turnstile Challenge
    const challengeSelectors = [
      'iframe[src*="cloudflare"]',
      'iframe[src*="turnstile"]',
      '#cf-challenge-running',
      '#challenge-running',
      '.cf-turnstile',
      'text="Verify you are human"',
      'text="Checking if the site connection is secure"',
    ];

    let challengeFound = false;
    for (const sel of challengeSelectors) {
      try {
        if ((await page.locator(sel).count()) > 0) {
          challengeFound = true;
          break;
        }
      } catch {}
    }

    if (challengeFound) {
      summary.challengeDetected = 'yes';
      console.log('⚠️ [Cloudflare] Challenge detected. Waiting 60s for resolution...');
      const cfWaitStart = Date.now();
      while (Date.now() - cfWaitStart < 60000) {
        await page.waitForTimeout(2000);
        let stuck = false;
        for (const sel of challengeSelectors) {
          if ((await page.locator(sel).count()) > 0) {
            stuck = true;
            break;
          }
        }
        if (!stuck) {
          console.log('✅ Cloudflare challenge cleared!');
          break;
        }
      }
    }

    // Step 3b: Select Direct Chat Tab
    const directChatSelectors = [
      'button:has-text("Direct Chat")',
      '[data-testid="direct-chat-tab"]',
      'a:has-text("Direct Chat")',
      'role=tab[name*="Direct Chat" i]',
    ];

    for (const sel of directChatSelectors) {
      try {
        const loc = page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          console.log(`[Tab] Ensuring Direct Chat tab selected: "${sel}"`);
          await loc.click();
          await page.waitForTimeout(1000);
          break;
        }
      } catch {}
    }

    // Step 4: Pick Target Model (Default: 'seedream 5.0 lite')
    console.log(`[Model] Selecting target model: "${TARGET_MODEL}"...`);
    const modelCandidates = [
      '[data-testid="model-select"]',
      '[data-testid="model-dropdown"]',
      'select[aria-label*="model" i]',
      'button[aria-label*="model" i]',
      'div[role="combobox"]',
      'div[class*="model-selector"]',
      'button:has-text("Select Model")',
    ];

    let modelSelectEl = '';
    for (const sel of modelCandidates) {
      try {
        const loc = page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          modelSelectEl = sel;
          break;
        }
      } catch {}
    }

    summary.modelSelector = modelSelectEl || 'model-select-default';
    if (modelSelectEl) {
      try {
        await page.locator(modelSelectEl).first().click();
        await page.waitForTimeout(500);

        // Search or click Seedream 5.0 Lite
        const modelOption = page.locator(`text=/${TARGET_MODEL}|seedream/i`).first();
        if ((await modelOption.count()) > 0) {
          await modelOption.click();
          console.log(`[Model] Selected: ${TARGET_MODEL}`);
        }
      } catch (err: any) {
        console.log(`[Model Selection Note] ${err?.message}`);
      }
    }

    // Step 5: Input Cleaned Prompt & Submit
    console.log(`[Prompt] Entering cleaned prompt: "${effectivePrompt}"`);
    const promptInputCandidates = [
      '[data-testid="prompt-input"]',
      'textarea[placeholder*="prompt" i]',
      'textarea[placeholder*="message" i]',
      'textarea[placeholder*="image" i]',
      'textarea',
    ];

    let promptInputSel = '';
    for (const sel of promptInputCandidates) {
      try {
        const loc = page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          promptInputSel = sel;
          break;
        }
      } catch {}
    }

    if (!promptInputSel) {
      throw new Error('Prompt input textarea could not be found.');
    }

    const promptLoc = page.locator(promptInputSel).first();
    await promptLoc.click();
    await promptLoc.fill('');

    // Randomized human keystroke delay (40-90ms)
    for (const char of effectivePrompt) {
      await page.keyboard.type(char, { delay: Math.floor(Math.random() * (90 - 40 + 1)) + 40 });
    }

    const submitCandidates = [
      '[data-testid="send-button"]',
      '[data-testid="generate-button"]',
      'button[type="submit"]',
      'button:has-text("Generate")',
      'button:has-text("Send")',
      'button[aria-label*="send" i]',
    ];

    let submitButtonSel = '';
    for (const sel of submitCandidates) {
      try {
        const loc = page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          submitButtonSel = sel;
          break;
        }
      } catch {}
    }

    summary.submitSelector = submitButtonSel || 'Enter key';
    const submitStart = Date.now();
    if (submitButtonSel) {
      await page.locator(submitButtonSel).first().click();
    } else {
      await page.keyboard.press('Enter');
    }
    timings.promptSubmit = Date.now() - submitStart;
    console.log(`[Timing] Prompt submitted in ${timings.promptSubmit}ms`);

    // Step 6: Wait for Result Image
    console.log('[Waiting] Awaiting generated image (timeout 120s)...');
    const imageCandidates = [
      '[data-testid="generated-image"] img',
      'div[class*="output"] img',
      'div[class*="chat-message"] img',
      'div[class*="prose"] img',
      'img[src^="http"]:not([src*="avatar"]):not([src*="logo"])',
      'img[src^="blob:"]',
      'img[src^="data:image"]',
    ];

    const imageReadyStart = Date.now();
    let foundImageLocator: any = null;
    let foundImageSelector = '';

    const waitDeadline = Date.now() + 120000;
    while (Date.now() < waitDeadline) {
      for (const sel of imageCandidates) {
        try {
          const loc = page.locator(sel).last();
          if ((await loc.count()) > 0 && (await loc.isVisible())) {
            const src = await loc.getAttribute('src');
            if (src && !src.includes('avatar') && !src.includes('svg+xml') && !src.includes('profile')) {
              foundImageLocator = loc;
              foundImageSelector = sel;
              break;
            }
          }
        } catch {}
      }

      if (foundImageLocator) break;
      await page.waitForTimeout(2000);
    }

    if (!foundImageLocator) {
      throw new Error('Image generation timed out after 120 seconds.');
    }

    timings.imageReady = Date.now() - imageReadyStart;
    summary.imageContainerSelector = foundImageSelector;

    // Step 7: Download image bytes with page cookies
    const downloadStart = Date.now();
    const imageSrc = await foundImageLocator.getAttribute('src');

    let imageBuffer: Buffer;
    if (imageSrc?.startsWith('data:image')) {
      const base64Data = imageSrc.replace(/^data:image\/\w+;base64,/, '');
      imageBuffer = Buffer.from(base64Data, 'base64');
    } else if (imageSrc?.startsWith('blob:')) {
      const base64 = await page.evaluate(async (url) => {
        const resp = await fetch(url);
        const blob = await resp.blob();
        return new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onloadend = () => resolve(reader.result as string);
          reader.onerror = reject;
          reader.readAsDataURL(blob);
        });
      }, imageSrc);
      imageBuffer = Buffer.from(base64.replace(/^data:image\/\w+;base64,/, ''), 'base64');
    } else if (imageSrc?.startsWith('http')) {
      const resp = await page.request.get(imageSrc);
      if (!resp.ok()) {
        throw new Error(`Failed to download image: HTTP ${resp.status()}`);
      }
      imageBuffer = await resp.body();
    } else {
      throw new Error(`Unknown image source format: ${imageSrc}`);
    }

    fs.writeFileSync(OUT_IMAGE, imageBuffer);
    timings.download = Date.now() - downloadStart;
    summary.fileSizeBytes = imageBuffer.length;

    // Provenance output as required
    console.log('\n====================================================');
    console.log('🖼️ GENERATION COMPLETED (Harness Output View)');
    console.log('====================================================');
    console.log(`Saved Image: file://${OUT_IMAGE}`);
    console.log(`model: ${TARGET_MODEL}`);
    console.log('generated on: Harness');
    console.log('====================================================\n');
  } catch (err: any) {
    console.error('❌ [Execution Error]', err?.message || err);
    if (page) {
      const timestamp = Date.now();
      await page.screenshot({ path: path.resolve(TMP_DIR, `error_${timestamp}.png`), fullPage: true }).catch(() => {});
      const html = await page.content().catch(() => '');
      fs.writeFileSync(path.resolve(TMP_DIR, `error_${timestamp}.html`), html);
    }
  } finally {
    if (context) {
      await context.close().catch(() => {});
    }

    timings.total = Date.now() - timings.start;
    summary.totalTimeSec = parseFloat((timings.total / 1000).toFixed(2));

    console.log('📊 EXECUTION SUMMARY');
    console.log(`- Session reused:             ${summary.sessionReused}`);
    console.log(`- Challenge dikha?            ${summary.challengeDetected}`);
    console.log(`- Cleaned prompt sent:        "${summary.cleanedPrompt}"`);
    console.log(`- Model selector:             ${summary.modelSelector}`);
    console.log(`- Total time:                 ${summary.totalTimeSec}s`);
    console.log(`- File size:                  ${(summary.fileSizeBytes / 1024).toFixed(1)} kb`);
  }
}

main().catch((e) => {
  console.error('Fatal crash:', e);
  process.exit(1);
});
