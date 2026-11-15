import type { Page, Locator } from 'playwright';
import { SELECTORS, recordSelectorFailure, recordSelectorSuccess } from './selectors.js';

export class ArenaPage {
  constructor(public page: Page) {}

  async ensureDirectChat(): Promise<void> {
    // Check if mode dropdown currently shows "Battle" or "Side-by-side"
    try {
      const modeBtn = this.page.locator('button:has-text("Battle"), button:has-text("Side-by-side")').first();
      if ((await modeBtn.count()) > 0 && (await modeBtn.isVisible())) {
        await modeBtn.click();
        await this.page.waitForTimeout(500);
        const directOption = this.page.locator('[role="menuitem"]:has-text("Direct Chat"), [role="option"]:has-text("Direct Chat"), div:has-text("Direct Chat")').first();
        if ((await directOption.count()) > 0) {
          await directOption.click();
          await this.page.waitForTimeout(1000);
          recordSelectorSuccess(SELECTORS.directChatTab.key);
          return;
        }
      }
    } catch {}

    const entry = SELECTORS.directChatTab;
    const candidates = [entry.preferred, ...entry.fallbacks];

    for (const sel of candidates) {
      try {
        const loc = this.page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          await loc.click();
          await this.page.waitForTimeout(800);
          recordSelectorSuccess(entry.key);
          return;
        }
      } catch {}
    }

    recordSelectorFailure(entry.key, candidates[0]);
  }

  async selectModel(targetModelName: string): Promise<string> {
    const entry = SELECTORS.modelDropdown;
    const candidates = [entry.preferred, ...entry.fallbacks];

    let clicked = false;
    for (const sel of candidates) {
      try {
        const loc = this.page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          await loc.click();
          await this.page.waitForTimeout(500);
          recordSelectorSuccess(entry.key);
          clicked = true;
          break;
        }
      } catch {}
    }

    if (!clicked) {
      recordSelectorFailure(entry.key, candidates[0]);
    }

    // Try search input inside dropdown if available
    try {
      const searchBox = this.page.locator('input[placeholder*="search" i], input[placeholder*="model" i], input[type="text"]').last();
      if ((await searchBox.count()) > 0 && (await searchBox.isVisible())) {
        await searchBox.fill(targetModelName);
        await this.page.waitForTimeout(400);
      }
    } catch {}

    // Try to click option matching target model or known arena image models
    try {
      const cleanName = targetModelName.replace(/[^a-zA-Z0-9]/g, '.*');
      const option = this.page.locator(`text=/${cleanName}|${targetModelName}|mai-image|grok|muse|seedream/i`).first();
      if ((await option.count()) > 0) {
        await option.scrollIntoViewIfNeeded();
        await option.click();
        await this.page.waitForTimeout(500);
        return targetModelName;
      }
    } catch {}

    return targetModelName;
  }

  async submitPrompt(promptText: string): Promise<void> {
    const inputEntry = SELECTORS.promptInput;
    const inputCandidates = [inputEntry.preferred, ...inputEntry.fallbacks];

    let inputLoc: Locator | null = null;
    for (const sel of inputCandidates) {
      try {
        const loc = this.page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          inputLoc = loc;
          recordSelectorSuccess(inputEntry.key);
          break;
        }
      } catch {}
    }

    if (!inputLoc) {
      recordSelectorFailure(inputEntry.key, inputCandidates[0]);
      throw new Error('Prompt input element could not be found.');
    }

    await inputLoc.click();
    await inputLoc.fill('');

    // Randomized human keystroke delay (40-90ms)
    for (const char of promptText) {
      const delay = Math.floor(Math.random() * (90 - 40 + 1)) + 40;
      await this.page.keyboard.type(char, { delay });
    }

    // Submit button
    const submitEntry = SELECTORS.submitButton;
    const submitCandidates = [submitEntry.preferred, ...submitEntry.fallbacks];

    let submitted = false;
    for (const sel of submitCandidates) {
      try {
        const loc = this.page.locator(sel).first();
        if ((await loc.count()) > 0 && (await loc.isVisible())) {
          await loc.click();
          recordSelectorSuccess(submitEntry.key);
          submitted = true;
          break;
        }
      } catch {}
    }

    if (!submitted) {
      recordSelectorFailure(submitEntry.key, submitCandidates[0]);
      await this.page.keyboard.press('Enter');
    }
  }

  async awaitImage(timeoutMs: number = 120000): Promise<{ imageLocator: Locator; imageSrc: string }> {
    const entry = SELECTORS.resultImage;
    const candidates = [entry.preferred, ...entry.fallbacks];

    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      for (const sel of candidates) {
        try {
          const loc = this.page.locator(sel).last();
          if ((await loc.count()) > 0 && (await loc.isVisible())) {
            const src = await loc.getAttribute('src');
            if (src && !src.includes('avatar') && !src.includes('svg+xml') && !src.includes('profile')) {
              recordSelectorSuccess(entry.key);
              return { imageLocator: loc, imageSrc: src };
            }
          }
        } catch {}
      }
      await this.page.waitForTimeout(1500);
    }

    recordSelectorFailure(entry.key, candidates[0]);
    throw new Error(`Timed out waiting for generated image after ${timeoutMs}ms.`);
  }
}
