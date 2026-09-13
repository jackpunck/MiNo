// 生成 MiNo 的应用图标（PNG + ICO）。
//
// tauri-build 在 Windows 上会把 icons/icon.ico 编译进可执行文件的资源段，
// 缺少该文件会直接导致 `cargo build` 失败，因此这个脚本是仓库自举的一部分。
//
// 用法：node tools/gen-icons.mjs
//
// 不依赖任何第三方包 —— PNG 用 zlib 手写，ICO 只是给 PNG 套一层容器。

import { deflateSync, crc32 } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'src-tauri', 'icons');

// ---------------------------------------------------------------------------
// PNG 编码
// ---------------------------------------------------------------------------

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body) >>> 0);
  return Buffer.concat([len, body, crc]);
}

function encodePng(rgba, size) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // colour type: RGBA
  // 10..12 = compression / filter / interlace, all 0

  // 每条扫描线前置一个 filter 字节（0 = None）
  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0;
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(
      raw, y * (stride + 1) + 1,
    );
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------------------
// 绘制
// ---------------------------------------------------------------------------

const lerp = (a, b, t) => a + (b - a) * t;

function insideRoundedRect(x, y, size, radius) {
  // 收缩半像素，避免边缘被切掉
  const min = 0, max = size;
  const cx = Math.min(Math.max(x, min + radius), max - radius);
  const cy = Math.min(Math.max(y, min + radius), max - radius);
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= radius * radius;
}

/** 点到线段的最短距离 */
function distToSegment(px, py, ax, ay, bx, by) {
  const vx = bx - ax, vy = by - ay;
  const wx = px - ax, wy = py - ay;
  const len2 = vx * vx + vy * vy;
  const t = len2 === 0 ? 0 : Math.min(1, Math.max(0, (wx * vx + wy * vy) / len2));
  const dx = px - (ax + t * vx);
  const dy = py - (ay + t * vy);
  return Math.sqrt(dx * dx + dy * dy);
}

/** 以 4x 超采样绘制，再盒式降采样，得到边缘平滑的图标 */
function renderIcon(size) {
  const SS = 4;
  const S = size * SS;

  const hi = new Uint8Array(S * S * 4);
  const radius = S * 0.235;
  const stroke = S * 0.088;

  // 对勾折线（归一化坐标）
  const pts = [
    [0.28, 0.53],
    [0.43, 0.68],
    [0.73, 0.33],
  ].map(([x, y]) => [x * S, y * S]);

  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const i = (y * S + x) * 4;
      const px = x + 0.5, py = y + 0.5;

      if (!insideRoundedRect(px, py, S, radius)) continue; // 保持透明

      // 靛蓝 → 紫罗兰 的纵向渐变
      const t = y / S;
      let r = lerp(79, 139, t);
      let g = lerp(70, 92, t);
      let b = lerp(229, 246, t);

      // 对勾：用白色覆盖
      let d = Infinity;
      for (let k = 0; k < pts.length - 1; k++) {
        d = Math.min(d, distToSegment(px, py, ...pts[k], ...pts[k + 1]));
      }
      const cov = Math.min(1, Math.max(0, stroke / 2 - d + 0.5));
      if (cov > 0) {
        r = lerp(r, 255, cov);
        g = lerp(g, 255, cov);
        b = lerp(b, 255, cov);
      }

      hi[i] = r; hi[i + 1] = g; hi[i + 2] = b; hi[i + 3] = 255;
    }
  }

  // 盒式降采样（对预乘前的颜色做平均；边缘处 alpha 一并平均）
  const out = new Uint8Array(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const j = ((y * SS + sy) * S + (x * SS + sx)) * 4;
          const av = hi[j + 3] / 255;
          r += hi[j] * av; g += hi[j + 1] * av; b += hi[j + 2] * av; a += av;
        }
      }
      const n = SS * SS;
      const o = (y * size + x) * 4;
      const aNorm = a / n;
      if (aNorm > 0) {
        out[o] = Math.round(r / a);       // 反预乘，避免边缘发暗
        out[o + 1] = Math.round(g / a);
        out[o + 2] = Math.round(b / a);
      }
      out[o + 3] = Math.round(aNorm * 255);
    }
  }

  return out;
}

// ---------------------------------------------------------------------------
// ICO 容器
// ---------------------------------------------------------------------------

function encodeIco(pngs) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);            // reserved
  header.writeUInt16LE(1, 2);            // type = icon
  header.writeUInt16LE(pngs.length, 4);  // count

  const dir = Buffer.alloc(16 * pngs.length);
  let offset = header.length + dir.length;

  pngs.forEach(({ size, data }, i) => {
    const e = i * 16;
    dir[e] = size >= 256 ? 0 : size;   // 0 表示 256
    dir[e + 1] = size >= 256 ? 0 : size;
    dir[e + 2] = 0;                    // 调色板数量
    dir[e + 3] = 0;                    // reserved
    dir.writeUInt16LE(1, e + 4);       // planes
    dir.writeUInt16LE(32, e + 6);      // bits per pixel
    dir.writeUInt32LE(data.length, e + 8);
    dir.writeUInt32LE(offset, e + 12);
    offset += data.length;
  });

  return Buffer.concat([header, dir, ...pngs.map((p) => p.data)]);
}

// ---------------------------------------------------------------------------

mkdirSync(OUT_DIR, { recursive: true });

const SIZES = [16, 32, 48, 64, 128, 256];
const pngs = SIZES.map((size) => ({ size, data: encodePng(renderIcon(size), size) }));

for (const { size, data } of pngs) {
  if ([32, 128, 256].includes(size)) {
    writeFileSync(join(OUT_DIR, `${size}x${size}.png`), data);
  }
}

// ICO 内嵌 PNG（Vista+ 支持，Windows 10/11 完全兼容）
writeFileSync(join(OUT_DIR, 'icon.ico'), encodeIco(pngs));

console.log(`已生成图标 → ${OUT_DIR}`);
for (const { size } of pngs) console.log(`  ${size}x${size}`);
console.log('  icon.ico');
