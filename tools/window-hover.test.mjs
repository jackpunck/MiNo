import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

async function setup(blocked) {
  const handlers = new Map();
  const events = new Map();
  const calls = [];
  const element = { value: '', addEventListener() {} };
  const context = vm.createContext({
    performance: { now: () => 10000 }, setTimeout, clearTimeout,
    document: {
      getElementById: () => element, addEventListener() {},
      documentElement: { addEventListener: (name, fn) => handlers.set(name, fn) },
    },
    window: { addEventListener() {}, __TAURI__: { event: {
      listen: (name, fn) => events.set(name, fn),
    } } },
  });
  const exports = {
    invoke: async (name, args) => {
      calls.push({ name, args });
      if (name === 'foreground_blocks_hover') return blocked;
      if (name === 'foreground_is_fullscreen') return false; // 普通窗口并非全屏
    },
    call() {}, state: { data: { settings: {} }, ui: {} },
    focusInput() {}, isEditing: () => false, commitEdit() {},
    closeSettings() {}, openSettings() {},
  };
  const module = new vm.SourceTextModule(readFileSync(new URL('../ui/window.js', import.meta.url), 'utf8'), { context });
  await module.link(() => new vm.SyntheticModule(Object.keys(exports), function () {
    for (const [name, value] of Object.entries(exports)) this.setExport(name, value);
  }, { context }));
  await module.evaluate();
  module.namespace.initWindow();
  return { handlers, events, calls };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

test('普通窗口在前台时，碰侧边不展开', async () => {
  const app = await setup(true);
  app.handlers.get('mouseenter')();
  await flush();
  assert.equal(app.calls.some(c => c.name === 'set_edge_collapsed'), false);
});

test('桌面或 MiNo 在前台时，碰侧边可以展开', async () => {
  const app = await setup(false);
  app.handlers.get('mouseenter')();
  await flush();
  assert.ok(app.calls.some(c => c.name === 'set_edge_collapsed' && c.args.collapsed === false));
});

test('主动唤起获得焦点不受其他窗口的悬停门控限制', async () => {
  const app = await setup(true);
  app.events.get('mino://focus-changed')({ payload: true });
  await flush();
  assert.ok(app.calls.some(c => c.name === 'set_edge_collapsed' && c.args.collapsed === false));
});
