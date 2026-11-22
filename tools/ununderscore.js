// ununderscore.js —— 把 assets/runtime 下所有以 '_' 开头的路径段改名，并同步改写引用
//
// 【为什么必须这么做（2026-11-22 定论）】
// aapt2 内建默认丢弃 `_*`，而 `ignoreAssetsPattern '!_*'` **并不能放行** ——
// 实测 3.4.0 包内下划线开头的路径段数量为 0，清单里也是 0。
// 也就是说这个 pattern 机制对我们的需求无效，继续调 pattern 是死路。
//
// 与其赌打包器行为，不如让文件本身不触发规则：
// 把 `_vendor` 改成 `AAA_vendor`（我们自己的前缀），
// 同时把 JS 里 `'../_vendor/...'` 这类引用一起替换掉。
// 这样 aapt 根本没有理由丢它，且不再依赖任何 ignoreAssetsPattern 配置。
//
// 为什么用 `AAA_` 前缀：
//   - 纯字母开头，不触发 `_*` / `.*` 任何默认规则
//   - 字母序靠前，不影响 require 解析
//   - 极不可能与 npm 包自身的真实文件名冲突
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
if (names.size === 0) { console.log('没有以 _ 开头的路径段，无需处理'); process.exit(0); }
for (const n of [...names].slice(0, 20)) console.log('  ' + n + ' -> ' + PREFIX + n);

if (names.size > 20) console.log('  …共 ' + names.size + ' 个');

// 第二遍：改名（从深到浅，避免父目录先改名导致子路径找不到）
function renameDeep(dir, rel) {
  const entries = fs.readdirSync(dir);
  // 先递归处理子目录
  for (const name of entries) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory() && !st.isSymbolicLink()) renameDeep(abs, rel ? rel + '/' + name : name);
  }
  // 再改自己这一层的名字
  for (const name of fs.readdirSync(dir)) {
    if (name.startsWith('_') && name !== '_') {
      const from = path.join(dir, name);
      const to = path.join(dir, PREFIX + name);
      if (fs.existsSync(to)) {
        console.log('⚠ 目标已存在，跳过: ' + to);
        continue;
      }
      fs.renameSync(from, to);
    }
  }
}
renameDeep(root, '');
console.log('✓ 目录改名完成');

// 第三遍：改写所有文本文件里的引用
// 注意只替换「路径段」形式：'/xxx' 或 './xxx' 或 '../xxx' 或 '"xxx'
const TEXT_EXT = new Set(['.js', '.mjs', '.cjs', '.json', '.map', '.ts', '.d.ts', '.md', '.txt']);
let changed = 0;
let scanned = 0;

function rewrite(dir) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory()) { rewrite(abs); continue; }
    if (!st.isFile()) continue;
    const ext = path.extname(name).toLowerCase();
    // .d.ts 的 extname 是 .ts，已包含；json/map 也改
    if (!TEXT_EXT.has(ext) && !name.endsWith('.d.ts')) continue;
    if (st.size > 8 * 1024 * 1024) continue; // 超大文件跳过（如 .map 巨型文件）
    scanned++;
    let src;
    try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { continue; }
    let out = src;
    for (const n of names) {
      // 匹配路径段边界：前面必须是 / ' " ` 或行首，后面必须是 / ' " ` 或空白
      // 用 split/join 避免正则转义问题
      out = out.split('"../' + n + '/').join('"../' + PREFIX + n + '/');
      out = out.split("'../" + n + '/').join("'../" + PREFIX + n + '/');
      out = out.split('"./' + n + '/').join('"./' + PREFIX + n + '/');
      out = out.split("'./" + n + '/').join("'./" + PREFIX + n + '/');
      out = out.split('"/' + n + '/').join('"/' + PREFIX + n + '/');
      out = out.split("'/" + n + '/').join("'/" + PREFIX + n + '/');
      out = out.split('\\"' + n + '\\/').join('\\"' + PREFIX + n + '\\/');
      // package.json 的 "files"/"exports" 里会写裸路径 "_vendor/..."（前面没 ./ 也没 /）。
      // 运行时不读这段，但改干净可以避免以后自己看花眼误判。
      out = out.split('"' + n + '/').join('"' + PREFIX + n + '/');
    }
    if (out !== src) {
      fs.writeFileSync(abs, out);
      changed++;
    }
  }
}
rewrite(root);
console.log('✓ 引用改写完成：扫描 ' + scanned + ' 个文件，改动 ' + changed + ' 个');

// 自检：目录里不该再有 _ 开头的路径段
const left = new Set();
collect(root, '', left);
if (left.size) {
  console.log('✗ 仍有未改名的路径段: ' + [...left].slice(0, 5).join(', '));
  process.exit(1);
}
console.log('✓ 自检通过：无 _ 开头路径段');

// 自检第二条（关键）：不能有漏改的引用。
// 上一步的替换只覆盖 ../xxx/ ./xxx/ "/xxx/ 这几种写法，
// 如果包代码里出现 require('pkg/_vendor/x') 这类包名内部路径就没改到。
// 与其在手机上才发现 Cannot find module，不如在这里就拦住。
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
    for (const n of names) {
      // 找所有出现 "_vendor" 形态但前面不是我们前缀的地方
      const needle = '/' + n + '/';
      let idx = 0;
      while ((idx = src.indexOf(needle, idx)) !== -1) {
        const before = src.slice(Math.max(0, idx - PREFIX.length), idx);
        if (before !== PREFIX) {
          dangling.push(path.relative(root, abs) + ' :: ' + src.slice(Math.max(0, idx - 40), idx + needle.length).replace(/\n/g, ' '));
          break;
        }
        idx += needle.length;
      }
    }
  }
}
scanRefs(root);
if (dangling.length) {
  console.log('✗ 有 ' + dangling.length + ' 处漏改的引用（会导致 Cannot find module）：');
  for (const d of dangling.slice(0, 10)) console.log('   ' + d);
  process.exit(1);
}
console.log('✓ 自检通过：无漏改引用');
