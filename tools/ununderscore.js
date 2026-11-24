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

// ── 定位 acorn（仅自检二用，拿不到就降级）──────────────────
// 【2026-11-23】不能用相对 root 的路径，CI 里 root 是 assets/runtime，acorn 在别处。
// 按优先级：环境变量 > root 下 > 从脚本自身向上逐层找 node_modules/acorn。
function loadAcorn() {
  const cands = [];
  if (process.env.ACORN_PATH) cands.push(process.env.ACORN_PATH);
  cands.push(path.join(root, 'node_modules/acorn'));
  // CI 真实位置：workspace/openclaw-runtime/node_modules/openclaw/node_modules/acorn
  cands.push(path.join(__dirname, '..', 'openclaw-runtime', 'node_modules', 'openclaw', 'node_modules', 'acorn'));
  let d = __dirname;
  for (let i = 0; i < 8; i++) {
    cands.push(path.join(d, 'node_modules/acorn'));
    const up = path.dirname(d);
    if (up === d) break;
    d = up;
  }
  for (const c of cands) {
    try { return require(c); } catch (_) {}
  }
  return null;
}
// ── 字符串区间扫描（自写词法器，不依赖任何第三方库）────────────
// 【2026-11-23】不能用 acorn：CI 里 acorn 不在 node_modules（openclaw 并不直接依赖它，
// 本机只是恰好有）。为一个改名脚本去 npm install acorn = 给构建加新依赖 = 新的失败点。
const { stringRanges } = require(path.join(__dirname, 'jsstrings.js'));
const acorn = loadAcorn(); // 仅用于「自检二」的标识符审计，可选

// ── 标识符污染审计（自检二）─────────────────────────────────────
// 3.7.0 就是因为 `AAA_instanceof` 这种「标识符被当路径改」而整个模块链崩。
// 优先用 acorn 扫 Identifier（最精确）；拿不到 acorn 时，
// 用自写词法器反推：找「不在字符串区间内、但形如 AAA_xxx」的 token。
// 两条路都能拦住本次这个 bug。
const identHits = [];
// 【2026-11-24 修复】解析失败的文件数。旧的实现里 `catch(_) { continue; }` 会静默跳过，
// 于是 jsonc-parser/UMD 头这类「acorn module 模式解析不了」的文件永远不会被审计到，
// 污染就是这样漏到手机上的。现在是 fail-closed：解析失败也走词法审计，审计不出来就报错。
const auditParseFail = [];
function runIdentAudit(rt) {
  identHits.length = 0;
  auditParseFail.length = 0;
  const IDENT_RE = new RegExp('(^|[^A-Za-z0-9_$])' + PREFIX + '_[A-Za-z0-9_$]*', 'g');
  // 用 acorn 的 token 流判定：只有 name token 才是标识符/属性名，
  // 字符串与注释 token 天然不算。这样既能覆盖 `export { X as Y }` 这种
  // 会触发 relink 错误的写法（parse 抛异常但 tokenizer 能跑完），
  // 也不受 sourceType 选择影响。
  function tokenAudit(src) {
    const hits = [];
    const tok = acorn.tokenizer(src, { ecmaVersion: 'latest', allowHashBang: true, allowAwaitOutsideFunction: true, allowReturnOutsideFunction: true });
    let t;
    while (true) {
      t = tok.getToken();
      if (!t || t.type.label === 'eof') break;
      if (t.type.label === 'name' && typeof t.value === 'string' && t.value.startsWith(PREFIX + '_')) {
        hits.push({ name: t.value, pos: t.start });
      }
    }
    return hits;
  }
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

      if (acorn) {
        let hits = null;
        try { hits = tokenAudit(src); }
        catch (e) { hits = null; }
        if (hits) {
          for (const h of hits) identHits.push(path.relative(rt, abs) + ' :: 标识符 ' + h.name + ' @' + h.pos);
        } else {
          // tokenizer 都跑不动（语法极端异常）：fail-closed，记下来当失败
          auditParseFail.push(path.relative(rt, abs));
        }
      } else {
        // 词法降级：在「代码区」找 AAA_ 开头的 token
        const ranges = stringRanges(src);
        const inString = (pos) => ranges.some(r => pos >= r.start && pos < r.end);
        IDENT_RE.lastIndex = 0;
        let m;
        while ((m = IDENT_RE.exec(src)) !== null) {
          const pos = m.index + m[1].length;
          if (!inString(pos)) {
            identHits.push(path.relative(rt, abs) + ' :: 代码区出现 ' + m[0].slice(m[1].length));
          }
        }
      }
      if (identHits.length > 20) return;
    }
  }
  scan(rt);
  if (auditParseFail.length) {
    console.log('✗ 自检二失败：' + auditParseFail.length + ' 个文件无法词法解析，不能保证没被污染（fail-closed）：');
    for (const f of auditParseFail.slice(0, 10)) console.log('   ' + f);
    return false;
  }
  if (identHits.length) {
    console.log('✗ 自检二失败：' + identHits.length + ' 处代码标识符被误改名（会导致 Export xxx is not defined）：');
    for (const h of identHits.slice(0, 10)) console.log('   ' + h);
    return false;
  }
  console.log('✓ 自检二通过：无代码标识符被误改名' + (acorn ? '（token 流）' : '（词法区间）'));
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
// 【2026-11-23】边界判据经历了两次修正，都是同一根因：
//   判据只想到了「目录名」（前后都是 /），没想到「带扩展名的文件名」。
//   ① `_stream_duplex.js` 在 `/lib/_stream_duplex.js` 里：名字后面是 "."，不是 "/"
//   ② 作为整个字符串时：`require('.../_stream_duplex.js')` 名字后面直接是字符串结尾
//      这个已被 after===undefined 覆盖；但如果是 `"a/_x.js,b"` 则后面是 ","。
// 所以这里把所有「路径分隔符类」字符都认了：/ . " ' ` , ; ) ] } 空白 结尾
//
// 【为什么允许 "." 不会误伤】names 里的名字来自真实文件名/目录名，
// 如 `_vendor`、`_stream_duplex.js`。它们都带足够特异性，
// 不会碰见于普通的属性访问（那不在字符串里，根本走不到这里）。
const PATH_END_OK = (ch) => ch === undefined || ch === '/' || ch === '.' ||
  ch === '"' || ch === "'" || ch === '`' || ch === ',' || ch === ';' ||
  ch === ')' || ch === ']' || ch === '}' || ch === ' ' || ch === '\t' ||
  ch === '\n' || ch === '\r' || ch === '?' || ch === '#';
function replaceInString(s, names) {
  let n = 0;
  let out = s;
  for (const nm of names) {
    let idx = 0;
    while ((idx = out.indexOf(nm, idx)) !== -1) {
      const before = idx === 0 ? '' : out[idx - 1];
      const after = out[idx + nm.length];
      const okBefore = idx === 0 || before === '/';
      if (okBefore && PATH_END_OK(after)) {
        out = out.slice(0, idx) + PREFIX + out.slice(idx);
        idx += PREFIX.length + nm.length;
        n++;
      } else {
        idx += nm.length;
      }
    }
  }
  return { out, n };
}

// ── JS 路线：只改字符串区间，代码和注释碰不到 ───────────────
function rewriteJs(src, names) {
  const ranges = stringRanges(src);
  if (!ranges.length) return { out: src, n: 0 };
  let n = 0;
  const edits = [];
  for (const r of ranges) {
    const inner = src.slice(r.start, r.end);
    const { out, n: k } = replaceInString(inner, names);
    if (k > 0) { edits.push([r.start, r.end, out]); n += k; }
  }
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
    if (isJs) {
      const r = rewriteJs(src, names);
      out = r.out; n = r.n; astUsed++;
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

if (!acorn) {
  // fail-closed：没有精确审计能力就不许改包。
  // 历史事故：这里原本是「悄悄降级」，于是 182 个文件的标识符被改坏，装到手机才曝。
  console.log('✗ 找不到 acorn，无法做标识符审计，拒绝改包。');
  console.log('   设置 ACORN_PATH 指向 acorn 目录后重跑。');
  process.exit(1);
}

rewrite(root);

// ── 保守否决：未改名的文件里不该出现 AAA_（2026-11-24）────────────────
// 典型事故面：名字清单来自「目录里以 _ 开头的路径段」。
// 如果某个文件里已经有 AAA_ 而后缀名不在清单里（上游就带、或者上一轮只改了一半），
// 那这个名字就永远改不到，而它可能已经被写进引用 → 运行时找不到。
// 这里直接拒绝，并把位置列出来——让别人（或我下次）一眼能看到是什么。
// 白名单：只放过「字符串内部」的 AAA_，因为那才是改名的合法产物。
(function defensivelyReject() {
  const { stringRanges } = require(path.join(__dirname, 'jsstrings.js'));
  const offenders = [];
  (function walk(d) {
    for (const nm of fs.readdirSync(d)) {
      const abs = path.join(d, nm);
      const st = fs.lstatSync(abs);
      if (st.isDirectory()) { walk(abs); continue; }
      if (!/\.(js|mjs|cjs)$/.test(nm)) continue;
      if (st.size > 8 * 1024 * 1024) continue;
      let src;
      try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { continue; }
      if (!src.includes(PREFIX + '_')) continue;
      let ranges;
      try { ranges = stringRanges(src); } catch (_) { ranges = []; }
      const re = new RegExp('(^|[^A-Za-z0-9_$])' + PREFIX + '_[A-Za-z0-9_$]*', 'g');
      let m;
      while ((m = re.exec(src)) !== null) {
        const pos = m.index + m[1].length;
        if (ranges.some(r => pos >= r.start && pos < r.end)) continue;
        offenders.push(path.relative(root, abs) + ' :: ' + m[0].slice(m[1].length));
        break;
      }
      if (offenders.length > 20) return;
    }
  })(root);
  if (offenders.length) {
    console.log('✗ 未知来源的 AAA_ 出现在代码区（名字清单里没有对应项，无法还原）:');
    for (const o of offenders.slice(0, 15)) console.log('   ' + o);
    process.exit(1);
  }
})();

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
// 【2026-11-23】必须和改写用同一套判据：只在「字符串区间」内找。
// 旧版全文件搜 /_name，把注释里、以及 readable-stream/lib/_stream_duplex.js
// 这种「不是路径段而是模块名一部分」的东西全报了 30 处误报。
const dangling = [];
function scanRefs(dir) {
  const REF_EXT = new Set(['.js', '.mjs', '.cjs', '.json']);
  function check(file, src, isJs) {
    // 候选：字面上出现 /_name（name 在前面的 names 里）
    const hits = [];
    for (const nm of names) {
      const needle = '/' + nm;
      let idx = 0;
      while ((idx = src.indexOf(needle, idx)) !== -1) {
        hits.push({ idx, nameStart: idx + 1, nm });
        idx += needle.length;
      }
    }
    if (!hits.length) return;
    // 取所有「可改区间」：JS 用字符串区间；非 JS 整文件
    let ranges = null;
    if (isJs) { try { ranges = stringRanges(src); } catch (_) { ranges = null; } }
    const inRange = (pos) => {
      if (!ranges) return true;              // 非 JS：不区分
      return ranges.some(r => pos >= r.start && pos < r.end);
    };
    for (const h of hits) {
      const nameStart = h.nameStart;
      if (!inRange(nameStart)) continue;     // 不在字符串里 = 不是真实路径
      const prefixStart = nameStart - PREFIX.length;
      const hasPrefix = prefixStart >= 0 && src.slice(prefixStart, nameStart) === PREFIX;
      if (hasPrefix) continue;               // 已经改过
      if (isGlobContext(src, nameStart)) continue;
      const ac = src[nameStart + h.nm.length];
      if (ac === '/' || ac === '.' || ac === '"' || ac === "'" || ac === '`' || ac === undefined || ac === ',' || ac === ';' || ac === ')' || ac === ']' || ac === '}' || ac === '\n' || ac === '\r' || ac === ' ' || ac === '\t' || ac === '?' || ac === '#') {
        dangling.push(file + ' :: ' + src.slice(Math.max(0, h.idx - 30), h.idx + h.nm.length + 12).replace(/\n/g, ' '));
        break;
      }
    }
  }
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory()) { scanRefs(abs); continue; }
    if (!st.isFile()) continue;
    const ext = path.extname(name).toLowerCase();
    if (!REF_EXT.has(ext)) continue;
    if (st.size > 8 * 1024 * 1024) continue;
    let src;
    try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { continue; }
    const isJs = ext === '.js' || ext === '.mjs' || ext === '.cjs';
    check(path.relative(root, abs), src, isJs);
    if (dangling.length > 30) return;
  }
}
scanRefs(root);
if (dangling.length) {
  console.log('✗ 自检三失败：' + dangling.length + ' 处漏改的真实路径引用：');
  for (const d of dangling.slice(0, 15)) console.log('   ' + d);
  process.exit(1);
}
console.log('✓ 自检三通过：无漏改的真实路径引用');
