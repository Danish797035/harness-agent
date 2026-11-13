/**
 * Strips conversational conversational fluff so only the real image prompt is sent.
 * Example: "generate a image for me a dog in delhi park" -> "a dog in delhi park"
 */
export function cleanPrompt(rawInput: string): string {
  let cleaned = (rawInput || '').trim();

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

  // Remove trailing or leading conversational remnants like "for me"
  cleaned = cleaned.replace(/^(for\s+me\s+)/i, '').trim();

  return cleaned.length > 0 ? cleaned : rawInput;
}
