// ununderscore.js —— 把 assets/runtime 下所有以 '_' 开头的路径段改名，并同步改写引用
//
// 【为什么必须这么做（2026-11-22 定论）】
// aapt2 内建默认丢弃 `_*`，而 `ignoreAssetsPattern '!_*'` **并不能放行** ——
// 实测 3.4.0 包内下划线开头的路径段数量为 0，清单里也是 0。
// 继续调 pattern 是死路。
//
// 对策：让文件本身不触发规则 —— `_vendor` → `AAA_vendor`，同步改写引用。
// 不再依赖任何打包器配置。
//
// 【2026-11-22 第二次修正：替换范围】
// 第一次自检报了 13 处「漏改」，逐条看过后分两类：
//   A. 真的要改：`dist/provider-*.js` 里 bundler 内联的
//      `.../node_modules/discord-api-types/payloads/v10/_interactions/...`
//      —— 这是真实使用路径，前面是 `node_modules/`，不是 `../`，旧规则漏了。
//   B. 绝不能改：package.json 里的 glob ignore 模式，
//      如 `"!**/__fixtures__/"`、`"**/__snapshots__/"`。
//      它们不是路径，是 npm 的排除模式；改了会让 npm 元数据变得无意义。
//
// 所以规则定为：
//   - 替换「路径段」形式：前面必须是 `/`（含 `../` `./` 的末段）或引号
//   - 不替换 glob 模式：前面是 `*` 或 `!` 的一律跳过
//
// 用法: node tools/ununderscore.js <assets/runtime 目录>

const fs = require('fs');
const path = require('path');

const PREFIX = 'AAA';
const root = process.argv[2];
if (!root) { console.log('用法: ununderscore.js <runtime目录>'); process.exit(2); }
if (!fs.existsSync(root)) { console.log('✗ 目录不存在: ' + root); process.exit(1); }

// 第一遍：收集所有需要改名的路径段（名字以 _ 开头，且不是 . / ..）
function collect(dir, rel, out) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const r = rel ? rel + '/' + name : name;
    const st = fs.lstatSync(abs);
    if (name.startsWith('_') && name !== '_') out.add(name);
    if (st.isDirectory() && !st.isSymbolicLink()) collect(abs, r, out);
  }
}

const names = new Set();
collect(root, '', names);
console.log('需要改名的路径段: ' + names.size);
if (names.size === 0) {
  // 【2026-11-22 第四次修正】不能直接 exit 0！
  // 这一点是真实漏洞：如果上一轮改名完了但引用没改干净，
  // 重跑时 find 不到 _ 开头目录 → 直接“无需处理”退出，
  // 永远不会报错，永远不会重试，等于检查等于没。
  // 正确做法：把「已知会被改过的名字」从目录里反推出来（AAA_ 前缀的去前缀），
  // 再用它们跑一遍引用自检。
  console.log('无待改名目录（可能已改过）；改用 AAA_ 前缀反推名单继续自检');
  function collectRenamed(dir) {
    for (const name of fs.readdirSync(dir)) {
      const abs = path.join(dir, name);
      const st = fs.lstatSync(abs);
      if (name.startsWith(PREFIX) && name.length > PREFIX.length) names.add(name.slice(PREFIX.length));
      if (st.isDirectory() && !st.isSymbolicLink()) collectRenamed(abs);
    }
  }
  collectRenamed(root);
  if (names.size === 0) { console.log('目录里没有 AAA_ 前缀条目，确实无需处理'); process.exit(0); }
  console.log('反推得到 ' + names.size + ' 个已改名路径段，继续自检');
} else {
  for (const n of [...names]) console.log('  ' + n + ' -> ' + PREFIX + n);
}

// 第二遍：改名（先递归子目录，再改本层，避免父目录先改名导致子路径失效）
function renameDeep(dir) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory() && !st.isSymbolicLink()) renameDeep(abs);
  }
  for (const name of fs.readdirSync(dir)) {
    if (name.startsWith('_') && name !== '_') {
      const from = path.join(dir, name);
      const to = path.join(dir, PREFIX + name);
      if (fs.existsSync(to)) { console.log('⚠ 目标已存在，跳过: ' + to); continue; }
      fs.renameSync(from, to);
    }
  }
}
renameDeep(root);
console.log('✓ 目录改名完成');

// 第三遍：改写文本文件里的引用
// 关键：只改「路径段」，不改 glob 模式。
//   `.../frame/_vendor/x.js`  → 改（前面是 /）
//   `"../_vendor/x.js"`       → 改（前面是引号）
//   `"!**/__fixtures__/"`     → 不改（前面是 * 或 !）
const TEXT_EXT = new Set(['.js', '.mjs', '.cjs', '.json', '.map', '.ts', '.md', '.txt']);
let changed = 0;
let scanned = 0;
let replaced = 0;
const writeFail = [];

function isGlobContext(src, idx) {
  // idx 指向待替换名字的起始位置。往前看最近的字符：
  // 如果是 * 或 ! 或 / 后再跟 *，说明是 glob 模式
  let j = idx - 1;
  while (j >= 0 && (src[j] === ' ' || src[j] === '\t')) j--;
  if (j >= 0 && (src[j] === '*' || src[j] === '!')) return true;
  // `!**/` 这种
  if (j >= 1 && src[j] === '/' && (src[j - 1] === '*' || src[j - 1] === '!')) return true;
  return false;
}

function rewriteText(src, names) {
  let out = '';
  let i = 0;
  let n = 0;
  while (i < src.length) {
    let hit = null;
    for (const nm of names) {
      // 路径段后面必须紧跟 / 或引号或空白或结尾；先做快速起点检查
      if (src.startsWith(nm, i)) {
        const after = i + nm.length;
        const ac = src[after];
        if (ac === undefined || ac === '/' || ac === '"' || ac === "'" || ac === ' ' || ac === '\\' || ac === '\n' || ac === '\r') {
          // 前面必须是路径分隔符或引号或空白，才是路径段；否则可能是更长标识符的一部分
          const bc = i > 0 ? src[i - 1] : undefined;
          if (bc === undefined || bc === '/' || bc === '"' || bc === "'" || bc === ' ' || bc === '\\' || bc === '\n' || bc === '\t') {
            if (!isGlobContext(src, i)) { hit = nm; break; }
          }
        }
      }
    }
    if (hit) {
      out += PREFIX + hit;
      i += hit.length;
      n++;
    } else {
      out += src[i];
      i++;
    }
  }
  return { out, n };
}

function rewrite(dir) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory()) { rewrite(abs); continue; }
    if (!st.isFile()) continue;
    const ext = path.extname(name).toLowerCase();
    if (!TEXT_EXT.has(ext) && !name.endsWith('.d.ts')) continue;
    if (st.size > 8 * 1024 * 1024) continue;
    scanned++;
    let src;
    try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { continue; }
    const { out, n } = rewriteText(src, names);
    if (out !== src) {
      try {
        fs.writeFileSync(abs, out);
      } catch (e) {
        // 【2026-11-22】旧版直接崩掉报个堆栈，看不出是哪个文件、什么原因。
        // 现在统一收集后汇报，让日志第一条就能定位。
        writeFail.push(path.relative(root, abs) + ' :: ' + e.code);
        continue;
      }
      changed++;
      replaced += n;
    }
  }
}
rewrite(root);
console.log('✓ 引用改写完成：扫描 ' + scanned + ' 个文件，改动 ' + changed + ' 个，替换 ' + replaced + ' 处');
if (writeFail.length) {
  console.log('✗ ' + writeFail.length + ' 个文件写入失败：');
  for (const w of writeFail.slice(0, 10)) console.log('   ' + w);
  process.exit(1);
}

// 自检一：目录里不该再有 _ 开头的路径段
const left = new Set();
collect(root, '', left);
if (left.size) {
  console.log('✗ 仍有未改名的路径段: ' + [...left].slice(0, 5).join(', '));
  process.exit(1);
}
console.log('✓ 自检通过：无 _ 开头路径段');

// 自检二：不能有漏改的真实路径引用。
// 【注意】只对「真实路径」报错：前面带 / 或引号的。
// glob 模式（前面是 * 或 !）跳过 —— 那些改了反而错。
const dangling = [];
function scanRefs(dir) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory()) { scanRefs(abs); continue; }
    if (!st.isFile()) continue;
    const ext = path.extname(name).toLowerCase();
    if (!['.js', '.mjs', '.cjs', '.json'].includes(ext)) continue;
    if (st.size > 8 * 1024 * 1024) continue;
    let src;
    try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { continue; }
    for (const nm of names) {
      // 找 `/_name` 形态（前面是 /）且前面不是 PREFIX
      const needle = '/' + nm;
      let idx = 0;
      while ((idx = src.indexOf(needle, idx)) !== -1) {
        const nameStart = idx + 1;
        const prefixStart = nameStart - PREFIX.length;
        const hasPrefix = prefixStart >= 0 && src.slice(prefixStart, nameStart) === PREFIX;
        if (!hasPrefix) {
          const ac = src[nameStart + nm.length];
          const isPathEnd = ac === '/' || ac === '"' || ac === "'" || ac === undefined || ac === ',' || ac === '\n' || ac === ' ';
          // 【2026-11-22 第三次修正】必须和 rewriteText 用同一个 glob 判据。
          // `"!**/__fixtures__/**"` 里 __fixtures__ 前面确实是 `/`，
          // 但再往前是 `**/` —— 那是 glob 模式，不是路径，不能报错。
          // 上一版漏了这个双闸，导致 5 处误报、构建白白跑完 18 分钟。
          if (isPathEnd && !isGlobContext(src, nameStart)) {
            dangling.push(path.relative(root, abs) + ' :: ' + src.slice(Math.max(0, idx - 30), idx + needle.length + 10).replace(/\n/g, ' '));
            break;
          }
        }
        idx += needle.length;
      }
      if (dangling.length > 30) break;
    }
  }
}
scanRefs(root);
if (dangling.length) {
  console.log('✗ 有 ' + dangling.length + ' 处漏改的真实路径引用（会导致 Cannot find module）：');
  for (const d of dangling.slice(0, 15)) console.log('   ' + d);
  process.exit(1);
}
console.log('✓ 自检通过：无漏改的真实路径引用');
