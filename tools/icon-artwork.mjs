// 两端共用的便签与手写 M 矢量源；坐标系 256×256。
export const artwork = [
  { color: '#F6D8A8', path: 'M60,4 L176,4 C183,4 188,7 193,12 L240,59 C248,67 252,73 252,84 L252,196 C252,232 232,252 196,252 L60,252 C24,252 4,232 4,196 L4,60 C4,24 24,4 60,4 Z' },
  { color: '#EAA16B', path: 'M176,4 C182,5 178,25 180,46 C181,62 190,70 207,70 L234,70 C243,70 249,74 252,80 C252,73 248,67 240,59 L193,12 C188,7 183,4 176,4 Z' },
  { color: '#805039', path: 'M40,187 C44,164 57,124 70,98 C76,86 83,72 91,72 C103,72 108,85 110,100 L115,125 C117,134 120,138 126,133 L155,106 C163,98 171,92 179,98 C187,103 187,117 190,131 C194,152 200,174 212,183 C217,187 222,188 227,187 C231,186 232,190 228,193 C210,204 191,194 180,177 C171,164 166,145 164,130 L134,157 C124,167 112,171 101,160 C91,150 91,131 88,113 C76,137 66,169 62,188 C60,206 37,204 40,187 Z' },
];

// 只解析本文件使用的 M/L/C/Z；曲线离散后供零依赖 PNG 绘制使用。
export function flatten(path) {
  const tokens = path.match(/[MLCZ]|-?\d+(?:\.\d+)?/g);
  const points = [];
  let i = 0, x = 0, y = 0;
  while (i < tokens.length) {
    const command = tokens[i++];
    if (command === 'Z') continue;
    if (command === 'M' || command === 'L') {
      x = Number(tokens[i++]); y = Number(tokens[i++]); points.push([x, y]);
    } else if (command === 'C') {
      const [ax, ay, bx, by, ex, ey] = tokens.slice(i, i + 6).map(Number);
      i += 6;
      for (let step = 1; step <= 24; step++) {
        const t = step / 24, u = 1 - t;
        points.push([u*u*u*x + 3*u*u*t*ax + 3*u*t*t*bx + t*t*t*ex,
          u*u*u*y + 3*u*u*t*ay + 3*u*t*t*by + t*t*t*ey]);
      }
      x = ex; y = ey;
    } else throw new Error(`不支持的路径指令：${command}`);
  }
  return points;
}
