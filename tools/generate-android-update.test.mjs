import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveVersion, validateMetadata, generateUpdate } from './generate-android-update.mjs';

test('desktop version default and independent Android overrides', () => {
  assert.deepEqual(resolveVersion('0.5.0', {}), { versionName: '0.5.0', versionCode: 500 });
  assert.deepEqual(resolveVersion('0.5.0', { ANDROID_VERSION_CODE: '501' }), { versionName: '0.5.0', versionCode: 501 });
  assert.deepEqual(resolveVersion('0.5.0', { ANDROID_VERSION_NAME: '0.6.1' }), { versionName: '0.6.1', versionCode: 601 });
  assert.deepEqual(resolveVersion('0.5.0', { ANDROID_VERSION_NAME: '1.2.3', ANDROID_VERSION_CODE: '999' }), { versionName: '1.2.3', versionCode: 999 });
  assert.equal(resolveVersion('210000.0.0', {}).versionCode, 2100000000);
  assert.equal(resolveVersion('0.0.0', { ANDROID_VERSION_CODE: '1' }).versionCode, 1);
  assert.throws(() => resolveVersion('210000.0.1', {}));
});

test('invalid versions and codes are rejected', () => {
  for (const version of ['', 'v0.5.0', '1.2', '01.2.3', '1.100.0', '1.2.100', '1.2.3-beta', '1.2.3\n', '210001.0.0']) {
    assert.throws(() => resolveVersion('0.5.0', { ANDROID_VERSION_NAME: version }));
  }
  for (const code of ['', '0', '-1', '1.5', '1e3', '01', '1\n', '2100000001', '9007199254740992']) {
    assert.throws(() => resolveVersion('0.5.0', { ANDROID_VERSION_CODE: code }));
  }
  assert.throws(() => resolveVersion('0.0.0', {}));
});

const valid = () => ({ schema: 1, versionName: '0.5.0', versionCode: 500, minSdk: 26,
  apkUrl: 'https://github.com/jackpunck/MiNo/releases/download/v0.5.0/MiNo_android_0.5.0.apk',
  size: 3, sha256: 'a'.repeat(64), notes: '' });

test('metadata validates numeric fields, SHA-256 and exact release asset URL', () => {
  assert.deepEqual(validateMetadata(valid()), valid());
  for (const [key, values] of Object.entries({ schema: [2, '1'], versionCode: [0, 1.2, '500', 2100000001, NaN, Infinity],
    minSdk: [25, '26'], size: [0, -1, 1.5, '3', Number.MAX_SAFE_INTEGER + 1], sha256: ['', 'A'.repeat(64), 'z'.repeat(64), 'a'.repeat(64) + '\n'],
    versionName: ['../bad', '01.5.0'], notes: [null, []], apkUrl: [
      'http://github.com/jackpunck/MiNo/releases/download/v0.5.0/MiNo_android_0.5.0.apk',
      valid().apkUrl.replace('github.com', 'github.com.evil.test'),
      valid().apkUrl.replace('jackpunck', 'someone'), valid().apkUrl + '?download=1',
      valid().apkUrl + '#fragment', valid().apkUrl.replace('0.5.0.apk', '0.6.0.apk'),
      valid().apkUrl.replace('https://', 'https://user@'),
    ] })) {
    for (const value of values) assert.throws(() => validateMetadata({ ...valid(), [key]: value }), `${key}: ${value}`);
  }
});

test('client download and release notes limits match the generated metadata', () => {
  assert.equal(validateMetadata({ ...valid(), size: 32 * 1024 * 1024 }).size, 32 * 1024 * 1024);
  assert.throws(() => validateMetadata({ ...valid(), size: 32 * 1024 * 1024 + 1 }));
  assert.equal(validateMetadata({ ...valid(), notes: 'n'.repeat(8000) }).notes.length, 8000);
  assert.throws(() => validateMetadata({ ...valid(), notes: 'n'.repeat(8001) }));
});

test('generator hashes real bytes; CLI writes JSON and rejects bad inputs', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'mino-update-'));
  try {
    const apk = join(dir, 'sample.apk');
    const output = join(dir, 'android-update.json');
    await writeFile(apk, 'abc');
    const metadata = await generateUpdate({ apk, tag: 'v0.5.0', desktopVersion: '0.5.0', env: {}, notes: '修复提醒' });
    assert.equal(metadata.size, 3);
    assert.equal(metadata.sha256, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    assert.equal(metadata.notes, '修复提醒');
    const cli = new URL('./generate-android-update.mjs', import.meta.url);
    const env = { ...process.env, ANDROID_VERSION_NAME: '0.5.0', ANDROID_VERSION_CODE: '500' };
    const run = (...args) => spawnSync(process.execPath, [fileURLToPath(cli), ...args], { env, encoding: 'utf8' });
    const result = run('--apk', apk, '--output', output, '--tag', 'v0.5.0');
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(await readFile(output, 'utf8')).sha256, metadata.sha256);
    assert.notEqual(run('--apk', apk, '--output', output, '--tag', '../bad').status, 0);
    assert.notEqual(run('--apk', apk, '--output', output, '--tag', 'v0.5.0', '--unknown', 'x').status, 0);
    assert.notEqual(run('--apk', apk, '--output', output).status, 0);
    assert.notEqual(run('--apk', apk, '--apk', apk, '--output', output, '--tag', 'v0.5.0').status, 0);
    assert.equal(JSON.parse(await readFile(output, 'utf8')).sha256, metadata.sha256, 'failed invocations leave existing metadata intact');
    await assert.rejects(generateUpdate({ apk: join(dir, 'missing.apk'), tag: 'v0.5.0', desktopVersion: '0.5.0', env: {} }));
    await writeFile(apk, '');
    await assert.rejects(generateUpdate({ apk, tag: 'v0.5.0', desktopVersion: '0.5.0', env: {} }));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
