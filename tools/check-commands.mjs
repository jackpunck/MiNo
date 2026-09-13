// 校验前端调的每个 command 都真的在 Rust 侧注册了，以及前端监听的每个事件
// 都真的有人 emit。
//
// invoke 一个不存在的 command 只会在运行时失败，而且前端往往把它吞在 catch 里，
// 表现成「点了没反应」。事件名对不上也一样 —— 托盘点「设置」没反应、输入框不再
// 自动聚焦，全程不抛任何错。启动时静态比对一次比实机发现便宜得多。
//
// 用法：node tools/check-commands.mjs

import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

// Rust 侧：generate_handler! 里注册的 command
const mainRs = readFileSync(join(root, 'src-tauri/src/main.rs'), 'utf8');
const block = mainRs.match(/generate_handler!\[([\s\S]*?)\]/);
if (!block) {
  console.error('未能在 main.rs 里找到 generate_handler!');
  process.exit(1);
}
const rust = new Set([...block[1].matchAll(/commands::(\w+)/g)].map((m) => m[1]));

// 前端：invoke('x') / call('x')
const uiDir = join(root, 'ui');
const js = new Set();
for (const f of readdirSync(uiDir).filter((f) => f.endsWith('.js'))) {
  const src = readFileSync(join(uiDir, f), 'utf8');
  for (const m of src.matchAll(/\b(?:invoke|call)\(\s*['"]([a-z_]+)['"]/g)) {
    js.add(m[1]);
  }
}

// 事件名：Rust emit 的每个事件，前端都得真的 listen 着。
//
// 只认 `.emit(` / `listen(` 上直接写字面量的形式。事件名一旦拼成变量、或者哪天
// 改用 `emit_to`（它的第一个参数是窗口 label，正则认不出来），这里就查不到了 ——
// 真要那么写，把下面的正则跟着扩一下。
const rsDir = join(root, 'src-tauri/src');
const emitted = new Set();
for (const f of readdirSync(rsDir).filter((f) => f.endsWith('.rs'))) {
  const src = readFileSync(join(rsDir, f), 'utf8');
  for (const m of src.matchAll(/\.emit\(\s*['"]([^'"]+)['"]/g)) {
    emitted.add(m[1]);
  }
}

const listened = new Set();
for (const f of readdirSync(uiDir).filter((f) => f.endsWith('.js'))) {
  const src = readFileSync(join(uiDir, f), 'utf8');
  for (const m of src.matchAll(/\blisten\(\s*['"]([^'"]+)['"]/g)) {
    listened.add(m[1]);
  }
}

const sorted = (s) => [...s].sort().join(', ');
console.log(`Rust commands (${rust.size}): ${sorted(rust)}`);
console.log();
console.log(`JS invokes    (${js.size}): ${sorted(js)}`);
console.log();
console.log(`Rust emits    (${emitted.size}): ${sorted(emitted)}`);
console.log();
console.log(`JS listens    (${listened.size}): ${sorted(listened)}`);
console.log();

const dangling = [...js].filter((c) => !rust.has(c));
const rustOnly = [...rust].filter((c) => !js.has(c));
const unheard = [...listened].filter((e) => !emitted.has(e));
const unlistened = [...emitted].filter((e) => !listened.has(e));

if (dangling.length) {
  console.error(`✗ 前端调用了未注册的 command: ${dangling.join(', ')}`);
} else {
  console.log('✓ 前端调用的每个 command 都已在 Rust 侧注册');
}

if (unheard.length) {
  console.error(`✗ 前端监听的事件没有任何地方 emit: ${unheard.join(', ')}`);
} else {
  console.log('✓ 前端监听的每个事件都有 Rust 侧 emit');
}

// Rust 独有的通常是给托盘/内部调用的，不算错，只做提示
if (rustOnly.length) {
  console.log(`· 仅 Rust 内部调用: ${rustOnly.join(', ')}`);
}
if (unlistened.length) {
  console.log(`· 仅 Rust emit、前端没监听: ${unlistened.join(', ')}`);
}

process.exit(dangling.length || unheard.length ? 1 : 0);
