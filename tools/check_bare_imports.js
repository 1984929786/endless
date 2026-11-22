// check_bare_imports.js —— 断言「顶层 dist chunk 的静态裸模块引用，包根 node_modules 里都能解析」
//
// 【为什么需要（2026-11-22 现场）】
// 报错：dist/client-Cm0jR4_W.js 懒加载时 MODULE_NOT_FOUND（@slack/web-api）。
// 根因：openclaw 的 postinstall 会把 dist/extensions/*/package.json 的依赖装到包根，
//       CI 里被 npm 的 install-scripts 策略拦掉没执行 → 包缺依赖。
//
// 【为什么不能只断言 @slack/web-api】
// slack 只是这次撞上的。同一机制影响所有扩展（discord/matrix/whatsapp...）。
// 断言个例只能保一次；断言规则保全部。
//
// 【判据为什么用 AST 而不是正则（2026-11-22 教训）】
// 正则版把模板字符串 `${defaultAgentId}`、代码片段 `)` `,` 全当成模块名，
// 88 个"裸模块"里 23 个是误报。用正则判"这是不是模块引用"本身就不成立。
// 改用 acorn AST：只看 ImportDeclaration/ExportNamedDeclaration 的 source，
// 以及 require()/import() 的字面量参数 —— 这些是语法层面确定的模块引用。
//
// 用法: node check_bare_imports.js <runtime目录>
// 退出码 1 = 有缺失

const fs = require('fs');
const path = require('path');

// 找 acorn。
// YWZJ_ACORN_DIR 可直接指向 acorn 包目录（CI 现场：openclaw-runtime/node_modules/acorn）。
// 否则按：root/node_modules/acorn → 本脚本上级几层 → 工作目录 → 全局。
function loadAcorn(root) {
  const tries = [];
  if (process.env.YWZJ_ACORN_DIR) tries.push(process.env.YWZJ_ACORN_DIR);
  tries.push(path.join(root, 'node_modules', 'acorn'));
  for (const b of [__dirname, path.join(__dirname, '..'), path.join(__dirname, '..', '..'), process.cwd()]) {
    tries.push(path.join(b, 'node_modules', 'acorn'));
  }
  for (const t of tries) {
    try { return require(t); } catch (_) {}
  }
  try { return require('acorn'); } catch (_) {}
  return null;
}

const root = process.argv[2];
if (!root) { console.log('用法: check_bare_imports.js <runtime目录>'); process.exit(2); }

const acorn = loadAcorn(root);
if (!acorn) { console.log('✗ 找不到 acorn，无法做 AST 判定（拒绝用正则猜）'); process.exit(2); }

const distDir = path.join(root, 'dist');
if (!fs.existsSync(distDir)) { console.log('✗ 没有 dist 目录: ' + distDir); process.exit(1); }

const builtin = new Set(require('module').builtinModules);

// 只扫顶层 dist 的 JS（扩展内部有自己的 node_modules，解析规则不同）
const files = [];
for (const name of fs.readdirSync(distDir)) {
  const abs = path.join(distDir, name);
  let st; try { st = fs.lstatSync(abs); } catch (_) { continue; }
  if (st.isFile() && /\.(mjs|cjs|js)$/.test(name)) files.push(abs);
}

function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node)) { for (const n of node) walk(n, visit); return; }
  visit(node);
  for (const k of Object.keys(node)) {
    if (k === 'type' || k === 'start' || k === 'end') continue;
    walk(node[k], visit);
  }
}

function specsOf(src) {
  const out = new Set();
  let ast;
  try {
    ast = acorn.parse(src, { ecmaVersion: 'latest', sourceType: 'module', allowHashBang: true });
  } catch (_) {
    // 解析失败（极少数语法 acorn 不支持）→ 明确跳过并计数，不静默
    return { specs: out, parsed: false };
  }
  walk(ast, (n) => {
    // import ... from "x" / export ... from "x"
    if ((n.type === 'ImportDeclaration' || n.type === 'ExportNamedDeclaration' || n.type === 'ExportAllDeclaration')
        && n.source && n.source.type === 'Literal' && typeof n.source.value === 'string') {
      out.add(n.source.value);
    }
    // import("x") / require("x")
    if (n.type === 'CallExpression') {
      const callee = n.callee;
      const isImport = callee && callee.type === 'Import';
      const isRequire = callee && callee.type === 'Identifier' && callee.name === 'require';
      if ((isImport || isRequire) && n.arguments.length && n.arguments[0].type === 'Literal'
          && typeof n.arguments[0].value === 'string') {
        out.add(n.arguments[0].value);
      }
    }
  });
  return { specs: out, parsed: true };
}

function pkgNameOf(spec) {
  if (!spec) return null;
  if (spec.startsWith('.') || spec.startsWith('/') || spec.startsWith('node:')) return null;
  if (/^[a-z]+:/.test(spec)) return null; // file: data: http: 等 URL 形式
  if (spec.startsWith('#')) return null;   // package.json imports 子路径
  const parts = spec.split('/');
  return spec.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
}

const searchRoots = [path.join(distDir, 'node_modules'), path.join(root, 'node_modules')];
function exists(name) {
  for (const r of searchRoots) {
    if (fs.existsSync(path.join(r, ...name.split('/'), 'package.json'))) return true;
  }
  return false;
}

const needed = new Map();
let unparsed = 0;
for (const f of files) {
  let src; try { src = fs.readFileSync(f, 'utf8'); } catch (_) { continue; }
  const { specs, parsed } = specsOf(src);
  if (!parsed) { unparsed++; continue; }
  for (const spec of specs) {
    const name = pkgNameOf(spec);
    if (!name || builtin.has(name)) continue;
    if (!needed.has(name)) needed.set(name, path.relative(root, f));
  }
}

console.log('顶层 dist chunk: ' + files.length + ' 个（解析失败 ' + unparsed + '）');
console.log('静态引用的裸模块: ' + needed.size + ' 个');

const missing = [];
for (const [name, from] of needed) if (!exists(name)) missing.push({ name, from });

if (missing.length) {
  console.log('✗ 有 ' + missing.length + ' 个裸模块在包根 node_modules 里找不到：');
  for (const m of missing) console.log('   ' + m.name + '   (被 ' + m.from + ' 引用)');
  process.exit(1);
}
console.log('✓ 自检通过：顶层 chunk 的静态裸模块引用全部可解析');
