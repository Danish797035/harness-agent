import { cleanPrompt } from '../src/arena/prompt-cleaner.ts';

const testCases = [
  {
    input: 'generate a image for me a dog in delhi park',
    expected: 'a dog in delhi park',
  },
  {
    input: 'please can you create an image of a red sports car',
    expected: 'a red sports car',
  },
  {
    input: 'draw me a cute cat drinking milk',
    expected: 'a cute cat drinking milk',
  },
  {
    input: 'make a photo of taj mahal at sunrise',
    expected: 'taj mahal at sunrise',
  },
  {
    input: 'give me an image of a futuristic cyberpunk city',
    expected: 'a futuristic cyberpunk city',
  },
  {
    input: 'a beautiful mountain landscape with snow',
    expected: 'a beautiful mountain landscape with snow',
  },
];

console.log('Testing Prompt Cleaner Logic...\n');
let passed = 0;

for (const { input, expected } of testCases) {
  const result = cleanPrompt(input);
  const ok = result.toLowerCase() === expected.toLowerCase();
  console.log(`Input:    "${input}"`);
  console.log(`Cleaned:  "${result}"`);
  console.log(`Result:   ${ok ? '✅ PASS' : '❌ FAIL (Expected: "' + expected + '")'}\n`);
  if (ok) passed++;
}

console.log(`Summary: ${passed}/${testCases.length} tests passed.`);
