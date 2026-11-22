// check_plugin_deps.js —— 断言「扩展声明的运行时依赖，在包根 node_modules 里都能解析」
//
// 【为什么需要（2026-11-22 现场）】
// 报错：Cannot find module '@slack/web-api'
//       imported from '.../runtime/dist/client-Cm0jR4_W.js'
// 根因：顶层 dist chunk 通过 jiti 懒加载 dist/extensions/*/ 的代码，
//       扩展 package.json 声明的运行时依赖必须能从包根 node_modules 解析到。
//       CI 里 openclaw 的 postinstall 被 npm 拦掉没跑 → 依赖从没装上。
//
// 【判据演进（三次，值得记住）】
// ① 正则扫 `import ... from "x"`：88 个候选里 23 个是模板字符串误报。
// ② acorn AST：本机 0 误报，但 CI 里 acorn 在 $DEST 外面，找不到 → exit 2，卡两轮。
// ③ 正则 + Node 的 require.resolve 实判：不再 OOM，但本机"能跑"的版本仍报 12 个
//    "缺失" —— 其中 node-llama-cpp、@mariozechner/pi-* 都是运行时按需加载的
//    可选依赖，本机也没装却照样能跑。**说明"扫源码 + 实判"这个判据本身错了：
//    它把惰性可选依赖也当成硬依赖。**
// ④ 现在（正确判据）：只检查 **dist/extensions/*/package.json 里声明过的** 依赖。
//    这正是上游 postinstall 的思路 —— 扩展明确声明的，就必须能解析；
//    没声明的，不管源码里怎么出现，都不是我的责任。
//
// 判据原则（SOUL 工程纪律五之二）：判据不可靠时换判据，不要换参数。
// 前三版我一直在修正则窗口和白名单，都是换参数；真正的问题是判据选错了。
//
// 用法: node check_plugin_deps.js <runtime目录>
// 退出码 1 = 有缺失

const fs = require('fs');
const path = require('path');

const root = process.argv[2];
if (!root) { console.log('用法: check_plugin_deps.js <runtime目录>'); process.exit(2); }

const extDir = path.join(root, 'dist', 'extensions');
const rootNM = path.join(root, 'node_modules');
if (!fs.existsSync(extDir)) { console.log('✗ 没有 extensions 目录: ' + extDir); process.exit(1); }

// 1. 收集扩展声明的运行时依赖（与 install_plugin_deps.js 同一判据）
const wanted = new Map();
let extCount = 0;
for (const id of fs.readdirSync(extDir)) {
  const pj = path.join(extDir, id, 'package.json');
  if (!fs.existsSync(pj)) continue;
  let p; try { p = JSON.parse(fs.readFileSync(pj, 'utf8')); } catch (_) { continue; }
  extCount++;
  const deps = { ...(p.dependencies || {}), ...(p.optionalDependencies || {}) };
  for (const name of Object.keys(deps)) {
    if (!wanted.has(name)) wanted.set(name, []);
    wanted.get(name).push(id);
  }
}
console.log('扩展 ' + extCount + ' 个，声明运行时依赖 ' + wanted.size + ' 个');

// 2. 实判：目录存在 + package.json 在
const missing = [];
for (const [name, from] of wanted) {
  const pj = path.join(rootNM, ...name.split('/'), 'package.json');
  if (!fs.existsSync(pj)) missing.push({ name, from });
}

if (missing.length) {
  console.log('✗ 有 ' + missing.length + ' 个扩展依赖在包根 node_modules 里找不到：');
  for (const m of missing) console.log('   ' + m.name + '   [' + m.from.join(',') + ']');
  process.exit(1);
}
console.log('✓ 自检通过：' + wanted.size + ' 个扩展运行时依赖全部可解析');
