/**
 * SELECTORS POLICY:
 * All DOM selectors live in this file only. No inline selectors anywhere else.
 * Each selector has a verified date comment and data-testid preference.
 * When a selector fails 3x in a row, a structured SELECTOR_DRIFT log is emitted.
 */

export interface SelectorEntry {
  key: string;
  verifiedDate: string;
  preferred: string;
  fallbacks: string[];
}

export const SELECTORS: Record<string, SelectorEntry> = {
  // Verified 2026-11-13
  directChatTab: {
    key: 'directChatTab',
    verifiedDate: '2026-11-13',
    preferred: '[data-testid="direct-chat-tab"]',
    fallbacks: [
      'button:has-text("Direct Chat")',
      'a:has-text("Direct Chat")',
      'role=tab[name*="Direct Chat" i]',
    ],
  },
  // Verified 2026-11-13
  modelDropdown: {
    key: 'modelDropdown',
    verifiedDate: '2026-11-13',
    preferred: '[data-testid="model-select"]',
    fallbacks: [
      '[data-testid="model-dropdown"]',
      'select[aria-label*="model" i]',
      'button[aria-label*="model" i]',
      'div[role="combobox"]',
      'div[class*="model-selector"]',
      'button:has-text("Select Model")',
    ],
  },
  // Verified 2026-11-13
  promptInput: {
    key: 'promptInput',
    verifiedDate: '2026-11-13',
    preferred: '[data-testid="prompt-input"]',
    fallbacks: [
      'textarea[placeholder*="prompt" i]',
      'textarea[placeholder*="message" i]',
      'textarea[placeholder*="image" i]',
      'textarea',
    ],
  },
  // Verified 2026-11-13
  submitButton: {
    key: 'submitButton',
    verifiedDate: '2026-11-13',
    preferred: '[data-testid="send-button"]',
    fallbacks: [
      '[data-testid="generate-button"]',
      'button[type="submit"]',
      'button:has-text("Generate")',
      'button:has-text("Send")',
      'button[aria-label*="send" i]',
    ],
  },
  // Verified 2026-11-13
  resultImage: {
    key: 'resultImage',
    verifiedDate: '2026-11-13',
    preferred: '[data-testid="generated-image"] img',
    fallbacks: [
      'div[class*="output"] img',
      'div[class*="chat-message"] img',
      'div[class*="prose"] img',
      'img[src^="http"]:not([src*="avatar"]):not([src*="logo"])',
      'img[src^="blob:"]',
      'img[src^="data:image"]',
    ],
  },
  // Verified 2026-11-13
  cloudflareChallenge: {
    key: 'cloudflareChallenge',
    verifiedDate: '2026-11-13',
    preferred: 'iframe[src*="cloudflare"]',
    fallbacks: [
      'iframe[src*="turnstile"]',
      '#cf-challenge-running',
      '#challenge-running',
      '.cf-turnstile',
      'text="Verify you are human"',
      'text="Checking if the site connection is secure"',
    ],
  },
};

// Drift tracking state
const failureCounts: Map<string, number> = new Map();

export function recordSelectorFailure(key: string, selectorUsed: string) {
  const current = (failureCounts.get(key) || 0) + 1;
  failureCounts.set(key, current);

  if (current >= 3) {
    console.warn(
      JSON.stringify({
        level: 'warn',
        type: 'SELECTOR_DRIFT',
        selectorKey: key,
        lastSelectorTried: selectorUsed,
        consecutiveFailures: current,
        timestamp: new Date().toISOString(),
        message: `Selector "${key}" has failed ${current} consecutive times. LM Arena DOM structure may have drifted!`,
      })
    );
  }
}

export function recordSelectorSuccess(key: string) {
  failureCounts.set(key, 0);
}
