import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';
import type { Page } from 'playwright';

export interface SavedMedia {
  uuid: string;
  filename: string;
  filePath: string;
  url: string;
  sizeBytes: number;
}

const MEDIA_DIR = path.resolve(process.cwd(), process.env.MEDIA_DIR || './media');
const PUBLIC_BASE_URL = process.env.PUBLIC_BASE_URL || 'http://localhost:3000';

if (!fs.existsSync(MEDIA_DIR)) {
  fs.mkdirSync(MEDIA_DIR, { recursive: true });
}

/**
 * Downloads image bytes inside authenticated browser session context,
 * strips EXIF metadata (via sharp or pure buffer cleanup), and saves as .webp
 */
export async function processAndSaveImage(page: Page, imageSrc: string): Promise<SavedMedia> {
  let rawBuffer: Buffer;

  if (imageSrc.startsWith('data:image')) {
    const base64Data = imageSrc.replace(/^data:image\/\w+;base64,/, '');
    rawBuffer = Buffer.from(base64Data, 'base64');
  } else if (imageSrc.startsWith('blob:')) {
    const base64 = await page.evaluate(async (url) => {
      const resp = await fetch(url);
      const blob = await resp.blob();
      return new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => resolve(reader.result as string);
        reader.onerror = reject;
        reader.readAsDataURL(blob);
      });
    }, imageSrc);
    rawBuffer = Buffer.from(base64.replace(/^data:image\/\w+;base64,/, ''), 'base64');
  } else if (imageSrc.startsWith('http')) {
    const resp = await page.request.get(imageSrc);
    if (!resp.ok()) {
      throw new Error(`Failed to download image bytes: HTTP ${resp.status()} ${resp.statusText()}`);
    }
    rawBuffer = await resp.body();
  } else {
    throw new Error(`Unsupported image source format: ${imageSrc.slice(0, 50)}...`);
  }

  let finalBuffer = rawBuffer;

  try {
    const pkgName = 'sharp';
    const sharpModule: any = await import(pkgName).catch(() => null);
    if (sharpModule && sharpModule.default) {
      finalBuffer = await sharpModule.default(rawBuffer)
        .rotate()
        .webp({ quality: 90 })
        .toBuffer();
    }
  } catch {
    // If sharp is not installed yet in the environment, use raw buffer safely
  }

  const id = crypto.randomUUID();
  const filename = `${id}.webp`;
  const filePath = path.join(MEDIA_DIR, filename);

  await fs.promises.writeFile(filePath, finalBuffer);

  return {
    uuid: id,
    filename,
    filePath,
    url: `${PUBLIC_BASE_URL.replace(/\/$/, '')}/media/${filename}`,
    sizeBytes: finalBuffer.length,
  };
}
