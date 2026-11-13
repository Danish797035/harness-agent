import Fastify from 'fastify';
import { registerRoutes } from './api/routes.js';
import { browserManager } from './browser/context.js';

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';

const app = Fastify({
  logger: {
    level: process.env.LOG_LEVEL || 'info',
  },
});

async function start() {
  try {
    await registerRoutes(app);

    // Warm up the pre-warmed background tab in the background
    browserManager
      .getOrInitSession('acct-1')
      .then(() => {
        app.log.info('🚀 Background Arena session pre-warmed and ready.');
      })
      .catch((err) => {
        app.log.warn({ err }, 'Warning: Initial background pre-warm failed. Will retry on first request.');
      });

    await app.listen({ port: PORT, host: HOST });
    app.log.info(`harness-image service running on http://${HOST}:${PORT}`);
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
}

// Graceful shutdown
const shutdown = async () => {
  app.log.info('Shutting down gracefully...');
  await browserManager.closeAll().catch(() => {});
  await app.close().catch(() => {});
  process.exit(0);
};

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

start();
