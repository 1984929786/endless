// install_plugin_deps.js —— 补装 openclaw 各扩展的运行时依赖到包根 node_modules
//
// 【为什么需要（2026-11-22 现场，两次踩坑）】
// 报错：Cannot find module '@slack/web-api'
//       imported from '.../runtime/dist/client-Cm0jR4_W.js'
//
// 机制：顶层 dist chunk 会通过 jiti 懒加载 dist/extensions/*/ 的代码，
//       所以扩展 package.json 里声明的运行时依赖必须能从「包根 node_modules」解析到。
//       上游提供了 postinstall-bundled-plugins.mjs 做这件事。
//
// 【为什么不用上游那个脚本（3.6.0 实测失败）】
// 它 cwd 到 openclaw 包目录跑 `npm install --omit=dev`，但 npm 仍会算
// **openclaw 自己 package.json 的 devDependencies** 的 peer 关系，于是撞上：
//   ERESOLVE: oxlint@1.94.0 要 oxlint-tsgolint>=7.0.2007，
//             但包里装的是 0.18.1（dev 依赖）
// 装不成。而且它 catch 住只打一行 warn（上游注释：交由 doctor 暴露），
// 所以我们加了 `set -e` 也照样一路绿到最后才炸。
//
// 【本脚本做法】
// 1. 扫 dist/extensions/*/package.json，收集 dependencies + optionalDependencies
// 2. 过滤掉包根已存在的（幂等，重跑安全）
// 3. 在**独立的临时目录**建一个只含这些依赖的 package.json，在那里 npm install
//    —— 不碰 openclaw 自己的 package.json，完全没有 dev 依赖干扰
// 4. 把装好的目录合并进包根 node_modules（已存在的不覆盖）
// 5. 装完逐个断言，缺一个就 exit 1
//
// 用法: node install_plugin_deps.js <runtime目录>
//   环境变量 NPM_PLATFORM_OS / NPM_PLATFORM_CPU 可覆盖平台（默认 linux/arm64）

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const root = process.argv[2];
if (!root) { console.log('用法: install_plugin_deps.js <runtime目录>'); process.exit(2); }

const extDir = path.join(root, 'dist', 'extensions');
const rootNM = path.join(root, 'node_modules');
if (!fs.existsSync(extDir)) { console.log('✗ 没有 extensions 目录: ' + extDir); process.exit(1); }

// 1. 收集
const wanted = new Map();
let extCount = 0;
for (const id of fs.readdirSync(extDir)) {
  const pj = path.join(extDir, id, 'package.json');
  if (!fs.existsSync(pj)) continue;
  let p; try { p = JSON.parse(fs.readFileSync(pj, 'utf8')); } catch (_) { continue; }
  extCount++;
  const deps = { ...(p.dependencies || {}), ...(p.optionalDependencies || {}) };
  for (const [name, ver] of Object.entries(deps)) {
    if (!wanted.has(name)) wanted.set(name, { ver, from: [] });
    wanted.get(name).from.push(id);
  }
}
console.log('扫描扩展 ' + extCount + ' 个，声明运行时依赖 ' + wanted.size + ' 个');

// 2. 过滤已存在
function has(name) {
  return fs.existsSync(path.join(rootNM, ...name.split('/'), 'package.json'));
}
const missing = [...wanted.entries()].filter(([n]) => !has(n));
if (missing.length === 0) {
  console.log('✓ 所有扩展依赖都已在包根 node_modules，无需安装');
  process.exit(0);
}
console.log('需要安装 ' + missing.length + ' 个：');
for (const [n, o] of missing) console.log('   ' + n + '@' + o.ver + '  [' + o.from.join(',') + ']');

// 3. 在隔离目录安装
const tmp = path.join(root, '.plugin-deps-tmp');
fs.rmSync(tmp, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });
const deps = {};
for (const [n, o] of missing) deps[n] = o.ver;
fs.writeFileSync(path.join(tmp, 'package.json'), JSON.stringify({
  name: 'ywzj-plugin-deps',
  private: true,
  version: '0.0.0',
  dependencies: deps,
}, null, 2));

const os = process.env.NPM_PLATFORM_OS || 'linux';
const cpu = process.env.NPM_PLATFORM_CPU || 'arm64';

// 关键：--no-audit --no-fund --omit=dev --ignore-scripts
// --ignore-scripts 是因为某些包（sharp/koffi 等）的 install 脚本会去下载
// 原生二进制或编译，在 CI 里既慢又可能失败；这些包在扩展里只做可选功能。
const args = ['install', '--no-audit', '--no-fund', '--omit=dev', '--ignore-scripts',
  '--no-package-lock', '--os=' + os, '--cpu=' + cpu];
console.log('=== 隔离安装: npm ' + args.join(' ') + ' ===');
const r = spawnSync('npm', args, { cwd: tmp, encoding: 'utf8', stdio: 'pipe', shell: false });
if (r.stdout) process.stdout.write(r.stdout.slice(-3000));
if (r.status !== 0) {
  console.log('✗ npm install 失败 (status=' + r.status + ')');
  if (r.stderr) console.log(r.stderr.slice(-4000));
  process.exit(1);
}

// 4. 合并到包根
const gotNM = path.join(tmp, 'node_modules');
if (!fs.existsSync(gotNM)) { console.log('✗ 隔离安装后没有 node_modules'); process.exit(1); }

function mergeDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const name of fs.readdirSync(src)) {
    const s = path.join(src, name), d = path.join(dst, name);
    if (name.startsWith('@')) { mergeDir(s, d); continue; }
    if (!fs.existsSync(d)) {
      fs.renameSync(s, d);
    } else {
      // 已存在：保留原有的（顶层 npm install 装的那份优先）
      const sPkg = path.join(s, 'package.json'), dPkg = path.join(d, 'package.json');
      if (fs.existsSync(sPkg) && !fs.existsSync(dPkg)) fs.renameSync(s, d);
    }
  }
}
console.log('=== 合并到包根 node_modules ===');
mergeDir(gotNM, rootNM);
fs.rmSync(tmp, { recursive: true, force: true });

// 5. 断言
const stillMissing = [...wanted.keys()].filter((n) => !has(n));
if (stillMissing.length) {
  console.log('✗ 合并后仍缺 ' + stillMissing.length + ' 个：');
  for (const n of stillMissing) console.log('   ' + n);
  process.exit(1);
}
console.log('✓ 扩展运行时依赖全部就位（' + wanted.size + ' 个）');
