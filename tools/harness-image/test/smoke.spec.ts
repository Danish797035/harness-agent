import { test, expect } from '@playwright/test';

const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:3000';

test.describe('harness-image API smoke suite', () => {
  test('GET /health returns healthy status and account cooldowns', async ({ request }) => {
    const res = await request.get(`${BASE_URL}/health`);
    expect(res.ok()).toBeTruthy();

    const body = await res.json();
    expect(body.status).toBe('ok');
    expect(body.needs_human).toBe(false);
    expect(typeof body.coolingAccounts).toBe('object');
  });

  test('GET /v1/models returns model allowlist including seedream-5.0-lite', async ({ request }) => {
    const res = await request.get(`${BASE_URL}/v1/models`);
    expect(res.ok()).toBeTruthy();

    const body = await res.json();
    expect(Array.isArray(body.data)).toBeTruthy();
    const modelIds = body.data.map((m: any) => m.id);
    expect(modelIds).toContain('seedream-5.0-lite');
  });

  test('POST /v1/images/generations with invalid model returns 400 with allowlist', async ({ request }) => {
    const res = await request.post(`${BASE_URL}/v1/images/generations`, {
      data: {
        prompt: 'a dog in delhi park',
        model: 'unknown-nonexistent-model',
      },
    });

    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.error).toBeDefined();
    expect(Array.isArray(body.error.allowed_models)).toBeTruthy();
    expect(body.error.allowed_models).toContain('seedream-5.0-lite');
  });
});
