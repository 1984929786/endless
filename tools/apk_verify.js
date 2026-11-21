// 校验 APK 内 openclaw 运行时是否完整。
//
// 为什么用外部脚本而不是 inline heredoc：
//   2026-11-22 在 build.yml 里内联 python heredoc 后，GitHub 直接判定
//   workflow「没有 workflow_dispatch 触发器」，dispatch 一律 422。
//   YAML 块标量里嵌入多行脚本太容易踩缩进/引号，代价是整轮构建。
//
// 判据：清单里的每一条都必须真在包里。缺一个 = 坏包。
// 包内多出的文件无害（aapt 可能附带产物），只报告。
const fs = require('fs');
const zlib = require('zlib');

const apk = process.argv[2];
const PREFIX = 'assets/openclaw/runtime/';
const MANIFEST = 'assets/openclaw/manifest.txt';

if (!apk) { console.log('用法: apk_verify.js <apk>'); process.exit(2); }

const b = fs.readFileSync(apk);

// 解析 zip 中央目录（不用 unzip -l：它的列对齐会被长文件名挤断）
let eocd = -1;
for (let i = b.length - 22; i >= Math.max(0, b.length - 70000); i--) {
  if (b.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
}
if (eocd < 0) { console.log('✗ 找不到 zip 中央目录'); process.exit(2); }

const count = b.readUInt16LE(eocd + 10);
let off = b.readUInt32LE(eocd + 16);
const entries = [];
for (let k = 0; k < count; k++) {
  const method = b.readUInt16LE(off + 10);
  const csize = b.readUInt32LE(off + 20);
  const lho = b.readUInt32LE(off + 42);
  const nl = b.readUInt16LE(off + 28), el = b.readUInt16LE(off + 30), cl = b.readUInt16LE(off + 32);
  entries.push({ name: b.toString('utf8', off + 46, off + 46 + nl), method, csize, lho });
  off += 46 + nl + el + cl;
}

function readEntry(en) {
  const lnl = b.readUInt16LE(en.lho + 26), lel = b.readUInt16LE(en.lho + 28);
  const start = en.lho + 30 + lnl + lel;
  const raw = b.slice(start, start + en.csize);
  return en.method === 0 ? raw : zlib.inflateRawSync(raw);
}

const inPkg = new Set();
let manifestRaw = null;
for (const en of entries) {
  if (en.name.startsWith(PREFIX) && !en.name.endsWith('/')) inPkg.add(en.name.slice(PREFIX.length));
  if (en.name === MANIFEST) manifestRaw = readEntry(en).toString('utf8');
}

if (manifestRaw === null) { console.log('✗ APK 里没有 ' + MANIFEST); process.exit(1); }
const mani = manifestRaw.split('\n').filter(x => x.trim());
const maniSet = new Set(mani);

console.log('清单 ' + mani.length + ' 条 / 包内 runtime 文件 ' + inPkg.size + ' 个');

const lack = [...maniSet].filter(x => !inPkg.has(x));
const extra = [...inPkg].filter(x => !maniSet.has(x));

if (lack.length) {
  console.log('✗ 清单有而包里没有 ' + lack.length + ' 个（App 会校验失败，缺一个都不行）:');
  for (const x of lack.slice(0, 15)) console.log('   ' + x);
  process.exit(1);
}
if (extra.length) {
  console.log('⚠ 包内多出 ' + extra.length + ' 个（无害，通常是 aapt 附带产物）:');
  for (const x of extra.slice(0, 5)) console.log('   ' + x);
}

// 回归断言：这两个目录曾被 AGP 的 ignoreAssetsPattern 静默丢弃
for (const need of [
  PREFIX + 'node_modules/openai/_vendor/',
  PREFIX + 'dist/extensions/discord/node_modules/@buape/carbon/node_modules/discord-api-types/_interactions/'
]) {
  if (!entries.some(en => en.name.startsWith(need))) {
    console.log('✗ 被丢弃: ' + need + ' —— ignoreAssetsPattern 未生效');
    process.exit(1);
  }
}
console.log('✓ 清单 ' + mani.length + ' 条全部在包内，_ 开头的目录也都在');
