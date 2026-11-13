export interface ModelInfo {
  id: string;
  displayName: string;
  provider: string;
}

export const ALLOWED_MODELS: Record<string, ModelInfo> = {
  'seedream-5.0-lite': {
    id: 'seedream-5.0-lite',
    displayName: 'Seedream 5.0 Lite',
    provider: 'harness',
  },
  'imagen-3': {
    id: 'imagen-3',
    displayName: 'Imagen 3',
    provider: 'harness',
  },
  'flux-1-schnell': {
    id: 'flux-1-schnell',
    displayName: 'FLUX.1 [schnell]',
    provider: 'harness',
  },
  'stable-diffusion-3.5': {
    id: 'stable-diffusion-3.5',
    displayName: 'Stable Diffusion 3.5 Large',
    provider: 'harness',
  },
};

export const DEFAULT_MODEL_ID = process.env.DEFAULT_MODEL || 'seedream-5.0-lite';

export function getModelInfo(modelId?: string): ModelInfo | null {
  const target = (modelId || DEFAULT_MODEL_ID).trim().toLowerCase();
  return ALLOWED_MODELS[target] || null;
}

export function listAvailableModels(): ModelInfo[] {
  return Object.values(ALLOWED_MODELS);
}
