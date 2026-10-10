import { createHash, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
export const root = fileURLToPath(new URL('../../../', import.meta.url));
export const driver = fileURLToPath(new URL('.', import.meta.url));
export const exec = promisify(execFile);
export const artifacts = JSON.parse(await readFile(new URL('artifacts.json', import.meta.url)));
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export async function verifiedDownload(artifact, path) {
  let bytes;
  try { bytes = await readFile(path); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (!bytes) {
    if (!artifact.url) throw new Error(`Place the pinned artifact in ${path}`);
    const response = await fetch(artifact.url, { signal: AbortSignal.timeout(180_000) });
    if (!response.ok) throw new Error(`Download failed: ${response.status} ${artifact.url}`);
    bytes = Buffer.from(await response.arrayBuffer());
    if (sha256(bytes) !== artifact.sha256) throw new Error(`Checksum mismatch: ${path}`);
    await mkdir(dirname(path), { recursive: true });
    const temporary = `${path}.${randomUUID()}.tmp`;
    await writeFile(temporary, bytes, { flag: 'wx' });
    await rename(temporary, path);
  }
  if (sha256(bytes) !== artifact.sha256) throw new Error(`Checksum mismatch: ${path}`);
  return bytes;
}
export function offlineUuid(name) {
  const bytes = createHash('md5').update(`OfflinePlayer:${name}`).digest();
  bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128;
  const h = bytes.toString('hex');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
