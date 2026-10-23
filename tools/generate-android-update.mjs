import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const MAX_CODE = 2100000000;

function versionParts(value) {
  if (typeof value !== 'string' || !VERSION.test(value) || value.trim() !== value) {
    throw new Error('versionName must be a canonical major.minor.patch version');
  }
  const parts = value.split('.').map(Number);
  if (!parts.every(Number.isSafeInteger) || parts[0] > 210000 || parts[1] > 99 || parts[2] > 99) {
    throw new Error('Version components out of range: major <= 210000, minor/patch <= 99');
  }
  return parts;
}

function positiveInteger(value, name, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < 1 || value > max) throw new Error(`Invalid ${name}`);
  return value;
}

export function resolveVersion(desktopVersion, env = process.env) {
  const versionName = env.ANDROID_VERSION_NAME ?? desktopVersion;
  const [major, minor, patch] = versionParts(versionName);
  const override = env.ANDROID_VERSION_CODE;
  if (override !== undefined && (!/^[1-9]\d*$/.test(override) || String(Number(override)) !== override)) throw new Error('Invalid ANDROID_VERSION_CODE');
  const versionCode = positiveInteger(override === undefined ? major * 10000 + minor * 100 + patch : Number(override), 'versionCode', MAX_CODE);
  return { versionName, versionCode };
}

function validateTag(tag) {
  if (typeof tag !== 'string' || !tag.startsWith('v')) throw new Error('Expected a v<major.minor.patch> release tag');
  versionParts(tag.slice(1));
  return tag;
}

export function validateMetadata(data) {
  if (data.schema !== 1 || data.minSdk !== 26) throw new Error('Expected schema 1 and minSdk 26');
  versionParts(data.versionName);
  positiveInteger(data.versionCode, 'versionCode', MAX_CODE);
  positiveInteger(data.size, 'size', 32 * 1024 * 1024);
  if (typeof data.sha256 !== 'string' || data.sha256.length !== 64 || !/^[a-f0-9]{64}$/.test(data.sha256)) throw new Error('Invalid SHA-256');
  if (typeof data.notes !== 'string' || data.notes.length > 8000) throw new Error('notes must be a string of at most 8000 characters');
  const url = new URL(data.apkUrl);
  const prefix = 'https://github.com/jackpunck/MiNo/releases/download/';
  const segments = url.pathname.split('/');
  const tag = validateTag(segments[5]);
  const expected = `${prefix}${tag}/MiNo_android_${data.versionName}.apk`;
  if (data.apkUrl !== expected || url.href !== expected) throw new Error('apkUrl must point to the matching jackpunck/MiNo GitHub release asset');
  return data;
}

export async function generateUpdate({ apk, tag, desktopVersion, env = process.env, notes = '' }) {
  const { versionName, versionCode } = resolveVersion(desktopVersion, env);
  validateTag(tag);
  const bytes = await readFile(apk);
  return validateMetadata({ schema: 1, versionName, versionCode, minSdk: 26,
    apkUrl: `https://github.com/jackpunck/MiNo/releases/download/${tag}/MiNo_android_${versionName}.apk`,
    size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), notes });
}

async function main() {
  const args = process.argv.slice(2);
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i];
    if (!['--apk', '--output', '--tag', '--notes'].includes(key) || args[i + 1] === undefined || key in options) {
      throw new Error('Usage: --apk <file> --output <file> --tag <vX.Y.Z> [--notes <text>]');
    }
    options[key] = args[i + 1];
  }
  for (const key of ['--apk', '--output', '--tag']) if (!options[key]) throw new Error(`Missing ${key}`);
  const config = JSON.parse(await readFile(new URL('../src-tauri/tauri.conf.json', import.meta.url), 'utf8'));
  const data = await generateUpdate({ apk: options['--apk'], tag: options['--tag'], desktopVersion: config.version, notes: options['--notes'] ?? '' });
  await writeFile(options['--output'], `${JSON.stringify(data, null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
