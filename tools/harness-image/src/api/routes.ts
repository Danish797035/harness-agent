import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { getModelInfo, listAvailableModels } from '../model/allowlist.js';
import { processGenerationJob } from '../queue/worker.js';
import { accountMutex } from '../browser/mutex.js';
import * as path from 'path';
import * as fs from 'fs';

interface GenerateBody {
  prompt: string;
  model?: string;
  n?: number;
  size?: string;
}

export async function registerRoutes(app: FastifyInstance) {
  // 1. POST /v1/images/generations
  app.post('/v1/images/generations', async (req: FastifyRequest<{ Body: GenerateBody }>, reply: FastifyReply) => {
    const { prompt, model, n, size } = req.body || {};

    if (!prompt || typeof prompt !== 'string' || prompt.trim().length === 0) {
      return reply.status(400).send({
        error: {
          message: 'Prompt is required and must be a non-empty string.',
          type: 'invalid_request_error',
        },
      });
    }

    const modelInfo = getModelInfo(model);
    if (!modelInfo) {
      const allowed = listAvailableModels().map((m) => m.id);
      return reply.status(400).send({
        error: {
          message: `Unknown model "${model}". Model must be one of allowed list.`,
          allowed_models: allowed,
          type: 'invalid_request_error',
        },
      });
    }

    try {
      const result = await processGenerationJob({
        prompt,
        model: modelInfo.id,
        n: n || 1,
        size: size || '1024x1024',
      });
      return reply.send(result);
    } catch (err: any) {
      if (err?.message?.includes('cooling down')) {
        return reply.status(503).header('Retry-After', '30').send({
          error: {
            message: err.message,
            type: 'server_cooldown',
          },
        });
      }

      req.log.error(err);
      return reply.status(500).send({
        error: {
          message: err?.message || 'Internal generation failure.',
          type: 'api_error',
        },
      });
    }
  });

  // 2. GET /v1/models
  app.get('/v1/models', async () => {
    const models = listAvailableModels();
    return {
      object: 'list',
      data: models.map((m) => ({
        id: m.id,
        object: 'model',
        created: 1731486000,
        owned_by: 'harness',
        display_name: m.displayName,
      })),
    };
  });

  // 3. GET /health
  app.get('/health', async () => {
    const cooldowns = accountMutex.getCooldownStatus();
    return {
      status: 'ok',
      timestamp: new Date().toISOString(),
      coolingAccounts: cooldowns,
      needs_human: false,
    };
  });

  // 4. POST /v1/images/edits (Stub)
  app.post('/v1/images/edits', async (_req, reply) => {
    return reply.status(501).send({
      error: {
        message: 'Image edits are currently not supported in this arena backend.',
        type: 'not_implemented',
      },
    });
  });

  // 5. Static Media File Serving
  const mediaDir = path.resolve(process.cwd(), process.env.MEDIA_DIR || './media');
  app.get('/media/:filename', async (req: FastifyRequest<{ Params: { filename: string } }>, reply) => {
    const filename = path.basename(req.params.filename);
    const filePath = path.join(mediaDir, filename);

    if (!fs.existsSync(filePath)) {
      return reply.status(404).send({ error: 'Image not found' });
    }

    reply.header('Content-Type', 'image/webp');
    reply.header('Cache-Control', 'public, max-age=2592000'); // 30 days
    return reply.send(fs.createReadStream(filePath));
  });
}
