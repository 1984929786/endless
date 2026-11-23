// ununderscore.js —— 把 assets/runtime 下以 '_' 开头的路径段改名，并同步改写引用
//
// 【为什么必须这么做（2026-11-22 定论）】
// aapt2 内建默认丢弃 `_*`，`ignoreAssetsPattern '!_*'` 放行无效。
// 对策：让文件本身不触发规则 —— `_vendor` → `AAA_vendor`，同步改写引用。
//
// 【2026-11-23 重构：换层级，用 AST，不用文本匹配】
// 3.7.0 装机报 `SyntaxError: Export 'AAA_instanceof' is not defined in module`。
// 根因：旧实现扫整个文件文本，把「前后是空格/引号且以 _ 开头」的 token 一律改名。
// 于是 zod 的 `function _instanceof(...)` 被改成了 `AAA_instanceof`，
// 而 import 方仍然按老名字拿 → 导出名对不上 → 整个模块链崩。
//
// 这是「选错层级」的第二次。第一次是 UI 中文化用 DOM 替换（该改源码）。
// 结论：**名字出现在三种地方：字符串字面量（路径）、代码标识符、注释。**
// 只有字符串字面量该改。用 acorn 解析出 Literal / TemplateElement，
// 只在这些节点的区间内替换，标识符和注释天然碰不到。
//
// 非 JS 文件（.json/.map/.md/.txt）没有代码语义，退回文本替换，
// 但用严格判据：必须处于「路径上下文」（前面是 / " ' 或 = 或 : 或 [ 或 , 或空白且后面紧跟 / " ' 或结尾）。
//
// 用法: sh tools/jn tools/ununderscore.js <assets/runtime 目录>

const fs = require('fs');
const path = require('path');

const PREFIX = 'AAA';
const root = process.argv[2];
if (!root) { console.log('用法: ununderscore.js <runtime目录>'); process.exit(2); }
if (!fs.existsSync(root)) { console.log('✗ 目录不存在: ' + root); process.exit(1); }

// ── 定位 acorn ────────────────────────────────────────────────
function loadAcorn() {
  const cands = [
    path.join(root, 'node_modules/acorn'),
    '/data/data/com.xundao.openclaw/files/runtime/openclaw/node_modules/acorn',
  ];
  for (const c of cands) {
    try { return require(c); } catch (_) {}
  }
  return null;
}
const acorn = loadAcorn();

// ── 标识符污染审计（自检二）─────────────────────────────────────
// 3.7.0 就是因为 `AAA_instanceof` 这种「标识符被当路径改」而整个模块链崩。
// 只要 AA_ 出现在 Identifier 位置，就说明有代码语义被破坏。
const identHits = [];
function runIdentAudit(rt) {
  identHits.length = 0;
  if (!acorn) { console.log('⚠ 找不到 acorn，跳过标识符审计'); return true; }
  function scan(dir) {
    for (const name of fs.readdirSync(dir)) {
      const abs = path.join(dir, name);
      const st = fs.lstatSync(abs);
      if (st.isDirectory()) { scan(abs); continue; }
      if (!st.isFile()) continue;
      const ext = path.extname(name).toLowerCase();
      if (!['.js', '.mjs', '.cjs'].includes(ext)) continue;
      if (st.size > 8 * 1024 * 1024) continue;
      let src;
      try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { continue; }
      if (!src.includes(PREFIX + '_')) continue;
      let ast;
      try { ast = acorn.parse(src, { ecmaVersion: 'latest', sourceType: 'module', allowHashBang: true }); }
      catch (_) { continue; }
      (function walk(node) {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node)) { for (const c of node) walk(c); return; }
        if (node.type === 'Identifier' && typeof node.name === 'string' && node.name.startsWith(PREFIX + '_')) {
          identHits.push(path.relative(rt, abs) + ' :: 标识符 ' + node.name);
        }
        for (const k in node) {
          if (k === 'start' || k === 'end') continue;
          const v = node[k];
          if (v && typeof v === 'object') walk(v);
        }
      })(ast);
      if (identHits.length > 20) return;
    }
  }
  scan(rt);
  if (identHits.length) {
    console.log('✗ 自检二失败：' + identHits.length + ' 处代码标识符被误改名（会导致 Export xxx is not defined）：');
    for (const h of identHits.slice(0, 10)) console.log('   ' + h);
    return false;
  }
  console.log('✓ 自检二通过：无代码标识符被误改名');
  return true;
}

// ── 第一遍：收集需要改名的路径段 ───────────────────────────────
function collect(dir, out) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (name.startsWith('_') && name !== '_') out.add(name);
    if (st.isDirectory() && !st.isSymbolicLink()) collect(abs, out);
  }
}
const names = new Set();
collect(root, names);
console.log('需要改名的路径段: ' + names.size);

if (names.size === 0) {
  // 幂等重跑：从 AAA_ 反推名单，继续做引用自检。
  // 若直接 exit 0，则「改名完了但引用没改干净」永远不会被发现。
  function collectRenamed(dir) {
    for (const name of fs.readdirSync(dir)) {
      const abs = path.join(dir, name);
      const st = fs.lstatSync(abs);
      if (name.startsWith(PREFIX) && name.length > PREFIX.length) names.add(name.slice(PREFIX.length));
      if (st.isDirectory() && !st.isSymbolicLink()) collectRenamed(abs);
    }
  }
  collectRenamed(root);
  if (names.size === 0) {
    console.log('目录里没有 AAA_ 前缀条目，跳过改名');
    // 【2026-11-23】不能直接 exit 0：若上一轮把标识符改坏了但文件名没改，
    // 这里提前退出就永远查不到。仍然要跑一遍自检二（标识符污染）。
    if (!runIdentAudit(root)) process.exit(1);
    console.log('✓ 仅做完标识符自检，结束');
    process.exit(0);  }
  console.log('无待改名目录；反推得到 ' + names.size + ' 个已改名路径段，继续改写与自检');
} else {
  for (const n of [...names]) console.log('  ' + n + ' -> ' + PREFIX + n);
}

// ── 第二遍：改名（先递归子目录再改本层，避免父目录先改名导致子路径失效）──
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

// ── 第三遍：只改字符串字面量 ──────────────────────────────────
// 判据：一个名字算「路径段」当且仅当它在字符串里，且
//   前一个字符 ∈ { / } 或字符串开头，后一个字符 ∈ { / } 或字符串结尾。
// 这样 `_vendor/x.js`、`../_vendor`、`node_modules/x/_y/z` 都被命中，
// 而 `_instanceof`（裸标识符，不在字符串里）永远不会被碰。

const TEXT_EXT = new Set(['.js', '.mjs', '.cjs', '.json', '.map', '.ts', '.md', '.txt']);
let scanned = 0, changed = 0, replaced = 0, parseFail = 0, astUsed = 0;
const writeFail = [];
const parseFailFiles = [];

// 在单个字符串值内替换路径段
function replaceInString(s, names) {
  let n = 0;
  let out = s;
  for (const nm of names) {
    let idx = 0;
    const needle = nm;
    while ((idx = out.indexOf(needle, idx)) !== -1) {
      const before = idx === 0 ? '' : out[idx - 1];
      const after = out[idx + needle.length];
      const okBefore = idx === 0 || before === '/';
      const okAfter = after === undefined || after === '/';
      if (okBefore && okAfter) {
        out = out.slice(0, idx) + PREFIX + out.slice(idx);
        idx += PREFIX.length + needle.length;
        n++;
      } else {
        idx += needle.length;
      }
    }
  }
  return { out, n };
}

// ── AST 路线：只碰 Literal / TemplateElement 节点 ─────────────
function rewriteWithAst(src, names) {
  const ast = acorn.parse(src, {
    ecmaVersion: 'latest',
    sourceType: 'module',
    allowHashBang: true,
  });
  const edits = [];
  let n = 0;
  (function walk(node) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) { for (const c of node) walk(c); return; }
    const t = node.type;
    if ((t === 'Literal' || t === 'StringLiteral') && typeof node.value === 'string' && node.start != null) {
      // 取原始源码片段，避免 value 与 raw 转义不一致
      const raw = src.slice(node.start, node.end);
      const quote = raw[0];
      if (quote === '"' || quote === "'") {
        const inner = raw.slice(1, -1);
        const { out, n: k } = replaceInString(inner, names);
        if (k > 0) {
          edits.push([node.start + 1, node.end - 1, out]);
          n += k;
        }
      }
    } else if (t === 'TemplateElement' && typeof node.value?.cooked === 'string' && node.start != null) {
      const { out, n: k } = replaceInString(node.value.cooked, names);
      if (k > 0) {
        // TemplateElement 的 start/end 覆盖 raw 内容（含 ` 或 }）之外的部分，
        // 直接用 node.value.raw 的偏移更稳：使用 start + 1 跳过反引号/花括号边界不成立，
        // 因此这里改用 raw 定位。
        const raw = node.value.raw;
        const { out: outRaw, n: k2 } = replaceInString(raw, names);
        if (k2 > 0) {
          edits.push([node.start, node.end, outRaw]);
          n += k2;
        }
      }
    }
    for (const k in node) {
      if (k === 'start' || k === 'end' || k === 'value' || k === 'raw') continue;
      const v = node[k];
      if (v && typeof v === 'object') walk(v);
    }
  })(ast);
  if (!edits.length) return { out: src, n: 0 };
  edits.sort((a, b) => b[0] - a[0]);
  let out = src;
  for (const [s, e, txt] of edits) out = out.slice(0, s) + txt + out.slice(e);
  return { out, n };
}

// ── 文本路线：非 JS 文件 ─────────────────────────────────────
// 严格判据：前后必须是「路径分隔符 / 引号 / 赋值边界」，且不能处于 glob 上下文。
function isGlobContext(src, idx) {
  let j = idx - 1;
  while (j >= 0 && (src[j] === ' ' || src[j] === '\t')) j--;
  if (j >= 0 && (src[j] === '*' || src[j] === '!')) return true;
  if (j >= 1 && src[j] === '/' && (src[j - 1] === '*' || src[j - 1] === '!')) return true;
  return false;
}
function rewriteWithText(src, names) {
  let out = '', i = 0, n = 0;
  while (i < src.length) {
    let hit = null;
    for (const nm of names) {
      if (!src.startsWith(nm, i)) continue;
      const after = i + nm.length;
      const ac = src[after];
      const okAfter = ac === undefined || ac === '/' || ac === '"' || ac === "'" || ac === '\\' || ac === '\n' || ac === '\r' || ac === ' ';
      if (!okAfter) continue;
      const bc = i > 0 ? src[i - 1] : undefined;
      const okBefore = bc === undefined || bc === '/' || bc === '"' || bc === "'" || bc === '\\' || bc === '\n' || bc === '\t' || bc === ' ';
      if (!okBefore) continue;
      if (isGlobContext(src, i)) continue;
      hit = nm; break;
    }
    if (hit) { out += PREFIX + hit; i += hit.length; n++; }
    else { out += src[i]; i++; }
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
    let out, n;
    const isJs = ext === '.js' || ext === '.mjs' || ext === '.cjs' || ext === '.ts' || name.endsWith('.d.ts');
    if (isJs && acorn) {
      try {
        const r = rewriteWithAst(src, names);
        out = r.out; n = r.n; astUsed++;
      } catch (e) {
        // 解析失败不能静默退回文本（那正是本次 bug 的来源）。
        // 记下来，最后统一报，让构建失败而不是产出坏包。
        parseFail++;
        parseFailFiles.push(path.relative(root, abs) + ' :: ' + e.message.slice(0, 90));
        continue;
      }
    } else {
      const r = rewriteWithText(src, names);
      out = r.out; n = r.n;
    }
    if (out !== src) {
      try { fs.writeFileSync(abs, out); }
      catch (e) { writeFail.push(path.relative(root, abs) + ' :: ' + e.code); continue; }
      changed++; replaced += n;
    }
  }
}

if (!acorn && names.size) {
  console.log('✗ 找不到 acorn，拒绝用文本替换处理 JS（那正是 3.7.0 崩掉的原因）');
  process.exit(1);
}
rewrite(root);
console.log('✓ 引用改写完成：扫描 ' + scanned + ' 个文件，改动 ' + changed + ' 个，替换 ' + replaced + ' 处'
  + (acorn ? '（AST 处理 ' + astUsed + ' 个 JS）' : ''));
if (parseFail) {
  console.log('✗ ' + parseFail + ' 个 JS 文件解析失败，拒绝继续：');
  for (const p of parseFailFiles.slice(0, 10)) console.log('   ' + p);
  process.exit(1);
}
if (writeFail.length) {
  console.log('✗ ' + writeFail.length + ' 个文件写入失败：');
  for (const w of writeFail.slice(0, 10)) console.log('   ' + w);
  process.exit(1);
}

// ── 自检一：目录里不该再有 _ 开头的路径段 ─────────────────────
const left = new Set();
collect(root, left);
if (left.size) {
  console.log('✗ 仍有未改名的路径段: ' + [...left].slice(0, 5).join(', '));
  process.exit(1);
}
console.log('✓ 自检一通过：无 _ 开头路径段');

// ── 自检二：JS 里不能出现「裸标识符被改名」的痕迹 ─────────────
if (!runIdentAudit(root)) process.exit(1);

// ── 自检三：不能有漏改的真实路径引用 ─────────────────────────
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
    // 只查字符串里出现的 /_name 形态
    for (const nm of names) {
      const needle = '/' + nm;
      let idx = 0;
      while ((idx = src.indexOf(needle, idx)) !== -1) {
        const nameStart = idx + 1;
        const prefixStart = nameStart - PREFIX.length;
        const hasPrefix = prefixStart >= 0 && src.slice(prefixStart, nameStart) === PREFIX;
        if (!hasPrefix && !isGlobContext(src, nameStart)) {
          const ac = src[nameStart + nm.length];
          if (ac === '/' || ac === '"' || ac === "'" || ac === undefined || ac === ',' || ac === '\n' || ac === ' ') {
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
  console.log('✗ 自检三失败：' + dangling.length + ' 处漏改的真实路径引用：');
  for (const d of dangling.slice(0, 15)) console.log('   ' + d);
  process.exit(1);
}
console.log('✓ 自检三通过：无漏改的真实路径引用');
