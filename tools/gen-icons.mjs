// 从共享矢量源生成 MiNo 的 PC（PNG / ICO / SVG）与 Android 图标。
//
// tauri-build 在 Windows 上会把 icons/icon.ico 编译进可执行文件的资源段，
// 缺少该文件会直接导致 `cargo build` 失败，因此这个脚本是仓库自举的一部分。
//
// 用法：node tools/gen-icons.mjs
//
// 不依赖任何第三方包 —— PNG 用 zlib 手写，ICO 只是给 PNG 套一层容器。

import { artwork, flatten } from './icon-artwork.mjs';
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

// 扫描线填充共享矢量路径，4 倍超采样；透明边缘按预乘色降采样。
function renderIcon(size) {
  const SS = 4, S = size * SS;
  const hi = new Uint8Array(S * S * 4);
  for (const {color, path} of artwork) {
    const points = flatten(path).map(([x,y]) => [x*S/256,y*S/256]);
    const rgb = color.match(/\w\w/g).map(v => parseInt(v,16));
    for (let y=0;y<S;y++) {
      const py=y+0.5, crossings=[];
      for (let i=0,j=points.length-1;i<points.length;j=i++) {
        const [ax,ay]=points[j], [bx,by]=points[i];
        if ((ay>py)!==(by>py)) crossings.push(ax+(py-ay)*(bx-ax)/(by-ay));
      }
      crossings.sort((a,b)=>a-b);
      for (let k=0;k+1<crossings.length;k+=2) {
        for (let x=Math.max(0,Math.ceil(crossings[k]-0.5));x<Math.min(S,Math.ceil(crossings[k+1]-0.5));x++) {
          hi.set([...rgb,255],(y*S+x)*4);
        }
      }
    }
  }
  const out=new Uint8Array(size*size*4);
  for (let y=0;y<size;y++) for (let x=0;x<size;x++) {
    let r=0,g=0,b=0,a=0;
    for (let sy=0;sy<SS;sy++) for (let sx=0;sx<SS;sx++) {
      const j=((y*SS+sy)*S+x*SS+sx)*4, alpha=hi[j+3]/255;
      r+=hi[j]*alpha;g+=hi[j+1]*alpha;b+=hi[j+2]*alpha;a+=alpha;
    }
    const o=(y*size+x)*4;
    if(a) {out[o]=Math.round(r/a);out[o+1]=Math.round(g/a);out[o+2]=Math.round(b/a);}
    out[o+3]=Math.round(a/(SS*SS)*255);
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

// SVG 是可编辑的独立图标；Android 前景完整置于自适应图标的安全圆内。
const paths = artwork.map(({color,path}) => '<path fill="'+color+'" d="'+path+'"/>').join('');
writeFileSync(join(OUT_DIR,'logo.svg'), '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">'+paths+'</svg>\n');
const RES=join(OUT_DIR,'../../android/app/src/main/res');
function vector(paths,scale,offset,size=108) {
  return '<?xml version="1.0" encoding="utf-8"?>\n<!-- 由 npm run icons 生成，请修改 tools/icon-artwork.mjs -->\n'+
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="'+size+'dp" android:height="'+size+'dp" android:viewportWidth="'+size+'" android:viewportHeight="'+size+'">\n'+
    '<group android:scaleX="'+scale+'" android:scaleY="'+scale+'" android:translateX="'+offset+'" android:translateY="'+offset+'">\n'+
    paths.map(({color,path})=>'<path android:fillColor="'+color+'" android:pathData="'+path+'"/>').join('\n')+'\n</group>\n</vector>\n';
}
writeFileSync(join(RES,'drawable/ic_launcher_foreground.xml'),vector(artwork,0.1875,30));
writeFileSync(join(RES,'drawable/ic_launcher_monochrome.xml'),vector([{...artwork[2],color:'#FFFFFF'}],0.25,22));
writeFileSync(join(RES,'drawable/ic_notification.xml'),vector([{...artwork[2],color:'#FFFFFF'}],0.09375,0,24));
writeFileSync(join(RES,'drawable/ic_launcher_background.xml'),'<?xml version="1.0" encoding="utf-8"?>\n<!-- 暖棕底与便签形成对比；由 npm run icons 生成 -->\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#805039" /></shape>\n');
console.log('已同步 Android 自适应图标、单色主题图标与通知图标');
