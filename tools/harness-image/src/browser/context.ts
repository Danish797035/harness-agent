import { chromium, type BrowserContext, type Page } from 'playwright';
import * as path from 'path';
import * as fs from 'fs';
import { ArenaPage } from '../arena/page-object.js';
import { SELECTORS } from '../arena/selectors.js';

export interface AccountSession {
  accountId: string;
  context: BrowserContext;
  prewarmedPage: Page;
  arenaPage: ArenaPage;
  lastUsedAt: number;
}

export class BrowserManager {
  private sessions: Map<string, AccountSession> = new Map();
  private baseProfileDir: string;

  constructor() {
    this.baseProfileDir = path.resolve(process.cwd(), process.env.PROFILES_DIR || './profiles');
    if (!fs.existsSync(this.baseProfileDir)) {
      fs.mkdirSync(this.baseProfileDir, { recursive: true });
    }
  }

  async getOrInitSession(accountId: string = 'acct-1'): Promise<AccountSession> {
    const existing = this.sessions.get(accountId);
    if (existing && !existing.prewarmedPage.isClosed()) {
      existing.lastUsedAt = Date.now();
      return existing;
    }

    const profileDir = path.join(this.baseProfileDir, accountId);
    if (!fs.existsSync(profileDir)) {
      fs.mkdirSync(profileDir, { recursive: true });
    }

    const isHeadless = process.env.HEADLESS === 'true';

    const context = await chromium.launchPersistentContext(profileDir, {
      headless: isHeadless,
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

    const page = context.pages()[0] || (await context.newPage());
    page.setDefaultTimeout(120000);

    const arenaUrl = process.env.ARENA_URL || 'https://lmarena.ai/?arena=image';
    await page.goto(arenaUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });

    // Handle Google Login if present
    const email = process.env.GOOGLE_EMAIL;
    const password = process.env.GOOGLE_PASSWORD;
    if (page.url().includes('accounts.google.com') && email && password) {
      await this.handleGoogleLogin(page, email, password);
    }

    // Cloudflare check
    await this.handleCloudflareChallenge(page);

    const arenaPage = new ArenaPage(page);
    await arenaPage.ensureDirectChat();

    const session: AccountSession = {
      accountId,
      context,
      prewarmedPage: page,
      arenaPage,
      lastUsedAt: Date.now(),
    };

    this.sessions.set(accountId, session);
    return session;
  }

  private async handleGoogleLogin(page: Page, email: string, pass: string) {
    try {
      const emailInput = page.locator('input[type="email"]');
      if ((await emailInput.count()) > 0) {
        await emailInput.fill(email);
        await page.keyboard.press('Enter');
        await page.waitForTimeout(2500);
      }
      const passInput = page.locator('input[type="password"]');
      if ((await passInput.count()) > 0) {
        await passInput.fill(pass);
        await page.keyboard.press('Enter');
        await page.waitForTimeout(4000);
      }
    } catch (err: any) {
      console.warn('[Login Error]', err?.message);
    }
  }

  private async handleCloudflareChallenge(page: Page) {
    const cfEntry = SELECTORS.cloudflareChallenge;
    const selectors = [cfEntry.preferred, ...cfEntry.fallbacks];

    for (const sel of selectors) {
      try {
        if ((await page.locator(sel).count()) > 0) {
          console.warn('⚠️ Cloudflare challenge detected! Waiting for resolution...');
          const deadline = Date.now() + 40000;
          while (Date.now() < deadline) {
            await page.waitForTimeout(2000);
            let stuck = false;
            for (const s of selectors) {
              if ((await page.locator(s).count()) > 0) {
                stuck = true;
                break;
              }
            }
            if (!stuck) break;
          }
          break;
        }
      } catch {}
    }
  }

  async closeAll() {
    for (const session of this.sessions.values()) {
      await session.context.close().catch(() => {});
    }
    this.sessions.clear();
  }
}

export const browserManager = new BrowserManager();
