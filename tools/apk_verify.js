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
// aapt 实际生效的过滤规则，必须和 app/app/build.gradle 里 androidResources.ignoreAssetsPattern 一致。
// 校验时用同一套规则把清单过滤一遍（= 「应该进包的文件」），再和包内实际件比对。
// 这样即使 aapt 真丢了东西，报出来的也是「规则下应该有的却没在包里」，指向真实故障点。
const GRADLE_FILE = process.argv[3] || 'app/app/build.gradle';
function readPattern() {
  try {
    const g = fs.readFileSync(GRADLE_FILE, 'utf8');
    const m = g.match(/ignoreAssetsPattern\s+'([^']*)'/);
    return m ? m[1] : null;
  } catch (_) { return null; }
}
// 规则实现集中在 tools/aapt_rule.js，和 make_manifest.js 共用同一份代码。
// 两边规则一旦不同源，就会出现「清单说该有、包里没有」的死循环。
const { isDropped, dropRules } = require('./aapt_rule.js');

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
const maniAll = manifestRaw.split('\n').filter(x => x.trim());
const pattern = readPattern();
if (!pattern) { console.log('✗ 读不到 ignoreAssetsPattern（' + GRADLE_FILE + '），无法判定 aapt 规则'); process.exit(1); }
console.log('aapt 丢弃规则: ' + (dropRules(pattern).map(x => x.source).join(' ') || '(无)'));
// 清单本身应该已经是过滤后的结果；这里再过滤一次只是为了兼容旧清单
const mani = maniAll.filter(x => !isDropped(x, pattern));
const ignoredByAapt = maniAll.length - mani.length;
const maniSet = new Set(mani);

console.log('清单 ' + maniAll.length + ' 条 / aapt 规则下应进包 ' + mani.length + ' 条（规则性跳过 ' +
  ignoredByAapt + ' 条）/ 包内 runtime 文件 ' + inPkg.size + ' 个');

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

// 回归断言：曾经被丢弃的 `_` / `.` 开头内容必须真的在包里。
// ⚠ 2026-11-22 教训：不要写死「某个包一定存在」的路径。
// 我曾断言 `node_modules/openai/_vendor/`，但这版运行时根本没装那个目录，
// 结果包是好的、断言却永远失败，白白浪费一轮 15 分钟构建 + 一次下载。
// 正确做法：从**清单里真实存在**的 `_` / `.` 开头条目取样，要求它们在包里。
const unders = mani.filter(x => x.split('/').some(s => /^[_]./.test(s)));
const dots = mani.filter(x => x.split('/').some(s => /^\.[^/]/.test(s)) && !/\/\.(git|svn)\//.test(x));
if (unders.length) {
  const hit = unders.filter(x => inPkg.has(x));
  if (hit.length !== unders.length) {
    const lost = unders.filter(x => !inPkg.has(x));
    console.log('✗ ' + lost.length + ' 个下划线开头文件被丢弃（应全在包里）:');
    for (const x of lost.slice(0, 10)) console.log('   ' + x);
    process.exit(1);
  }
  console.log('✓ ' + unders.length + ' 个下划线开头文件全在包里');
}
if (dots.length) {
  const lost = dots.filter(x => !inPkg.has(x));
  if (lost.length) {
    console.log('✗ ' + lost.length + ' 个点开头文件被丢弃:');
    for (const x of lost.slice(0, 10)) console.log('   ' + x);
    process.exit(1);
  }
  console.log('✓ ' + dots.length + ' 个点开头文件全在包里');
}
console.log('✓ 清单 ' + mani.length + ' 条全部在包内');
