import { DatabaseSync } from 'node:sqlite';
import * as crypto from 'crypto';
import * as path from 'path';
import * as fs from 'fs';

const DB_PATH = path.resolve(process.cwd(), './cache.sqlite');
const CACHE_TTL_DAYS = parseInt(process.env.CACHE_TTL_DAYS || '7', 10);
const TTL_MS = CACHE_TTL_DAYS * 24 * 60 * 60 * 1000;

export interface CachedImage {
  hash: string;
  url: string;
  model: string;
  createdAt: number;
}

export class PromptCache {
  private db: DatabaseSync;

  constructor() {
    this.db = new DatabaseSync(DB_PATH);
    this.init();
  }

  private init() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS prompt_cache (
        hash TEXT PRIMARY KEY,
        url TEXT NOT NULL,
        model TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_cache_created ON prompt_cache(created_at);
    `);
  }

  static createHash(prompt: string, model: string, size: string = '1024x1024'): string {
    return crypto
      .createHash('sha256')
      .update(`${prompt.trim().toLowerCase()}::${model.trim().toLowerCase()}::${size.trim().toLowerCase()}`)
      .digest('hex');
  }

  get(hash: string): CachedImage | null {
    const stmt = this.db.prepare('SELECT hash, url, model, created_at FROM prompt_cache WHERE hash = ?');
    const row = stmt.get(hash) as { hash: string; url: string; model: string; created_at: number } | undefined;

    if (!row) return null;

    const age = Date.now() - row.created_at;
    if (age > TTL_MS) {
      // Expired TTL
      this.delete(hash);
      return null;
    }

    return {
      hash: row.hash,
      url: row.url,
      model: row.model,
      createdAt: row.created_at,
    };
  }

  set(hash: string, url: string, model: string) {
    const stmt = this.db.prepare(`
      INSERT OR REPLACE INTO prompt_cache (hash, url, model, created_at)
      VALUES (?, ?, ?, ?)
    `);
    stmt.run(hash, url, model, Date.now());
  }

  delete(hash: string) {
    const stmt = this.db.prepare('DELETE FROM prompt_cache WHERE hash = ?');
    stmt.run(hash);
  }

  purgeExpired() {
    const cutoff = Date.now() - TTL_MS;
    const stmt = this.db.prepare('DELETE FROM prompt_cache WHERE created_at < ?');
    stmt.run(cutoff);
  }
}

export const promptCache = new PromptCache();
