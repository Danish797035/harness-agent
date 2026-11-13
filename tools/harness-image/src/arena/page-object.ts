import type { Page, Locator } from 'playwright';
import { SELECTORS, recordSelectorFailure, recordSelectorSuccess } from './selectors.js';

export class ArenaPage {
  constructor(public page: Page) {}

  async ensureDirectChat(): Promise<void> {
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

    // Try to click option matching target model or seedream
    try {
      const option = this.page.locator(`text=/${targetModelName}|seedream/i`).first();
      if ((await option.count()) > 0) {
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
