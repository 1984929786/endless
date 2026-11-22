#!/usr/bin/env node
/**
 * prune_runtime.js —— 按「启动期实际加载」为判据裁剪运行时。
 *
 * ===== 判据的演进史（每次都是被实测打回来的）=====
 * v1 「所有扩展声明的依赖都必须存在」→ 18176 文件，条目数 8.9 万，撞 zip 65535 上限。
 * v2 「只装 dist 静态 import 的包」→ 正确，但还不够。
 * v3 「只留启用的扩展目录」→ 实测崩：`openclaw plugins list` 报
 *     Missing bundled chat channel metadata for: telegram, whatsapp, ...
 *     原因：buildChatChannelMetaById() 遍历 listBundledPluginMetadata()，
 *     对 CHAT_CHANNEL_ORDER 里每个内置 channel 都要求有 package.json。
 *     → **扩展的元数据文件不能删，只有 node_modules 里的第三方包能删。**
 *
 * ===== 现行判据（三条）===== *   1. dist/ 根下、dist/extensions 各扩展目录下的 .js/.json 一律保留（代码 + 元数据）
 *   2. dist/extensions 各扩展目录 node_modules 下只删「不被任何代码静态引用」的包
 *   3. 顶层 node_modules/ 只保留「被静态引用的包 + 传递依赖」
 *
 * 用法：node prune_runtime.js <runtime目录> [--dry-run|--apply]
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
const MODE = process.argv[3] || "--dry-run";

if (!ROOT) {
  console.error("用法: node prune_runtime.js <runtime目录> [--dry-run|--apply]");
  process.exit(2);
}

// ---------- 工具 ----------
function walkFiles(dir) {
  const out = [];
  const st = [dir];
  while (st.length) {
    const cur = st.pop();
    let ents;
    try {
      ents = fs.readdirSync(cur, { withFileTypes: true });
    } catch (_) {
      continue;
    }
    for (const e of ents) {
      const p = path.join(cur, e.name);
      if (e.isDirectory()) st.push(p);
      else if (e.isFile()) out.push(p);
    }
  }
  return out;
}
const countFiles = (d) => (fs.existsSync(d) ? walkFiles(d).length : 0);

function rmrf(p) {
  const files = walkFiles(p);
  const dirs = new Set();
  for (const f of files) {
    dirs.add(path.dirname(f));
    try {
      fs.unlinkSync(f);
    } catch (_) {}
  }
  for (const d of [...dirs].sort((a, b) => b.length - a.length)) {
    try {
      fs.rmdirSync(d);
    } catch (_) {}
  }
  try {
    fs.rmdirSync(p);
  } catch (_) {}
}

// ---------- 1. 静态依赖 ----------
const staticDepsPath = path.join(ROOT, ".static-deps.json");
if (!fs.existsSync(staticDepsPath)) {
  console.error("✗ 缺少 .static-deps.json，先跑 scan_static_deps.js");
  process.exit(2);
}
const requiredPkgs = new Set(Object.keys(JSON.parse(fs.readFileSync(staticDepsPath, "utf8"))));

// ---------- 2. 传递依赖闭包 ----------
const nmDirs = [path.join(ROOT, "node_modules")];
const extRoot = path.join(ROOT, "dist", "extensions");
if (fs.existsSync(extRoot)) {
  for (const id of fs.readdirSync(extRoot)) {
    const nm = path.join(extRoot, id, "node_modules");
    if (fs.existsSync(nm)) nmDirs.push(nm);
  }
}

function findPkgDir(name) {
  // 先顶层，再各扩展自带的
  for (const nm of nmDirs) {
    const d = path.join(nm, ...name.split("/"));
    if (fs.existsSync(path.join(d, "package.json"))) return d;
  }
  return null;
}

const closure = new Set();
const queue = [...requiredPkgs];
const missing = [];
while (queue.length) {
  const name = queue.pop();
  if (closure.has(name)) continue;
  closure.add(name);
  const dir = findPkgDir(name);
  if (!dir) {
    missing.push(name);
    continue;
  }
  let pkg;
  try {
    pkg = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
  } catch (_) {
    continue;
  }
  const deps = {
    ...(pkg.dependencies || {}),
    ...(pkg.optionalDependencies || {}),
    ...(pkg.peerDependencies || {}),
  };
  for (const d of Object.keys(deps)) if (!closure.has(d)) queue.push(d);
}

// ---------- 3. 计划 ----------
// 3a. 顶层 node_modules：删闭包外的
const rootNM = path.join(ROOT, "node_modules");
const topDelete = [];
const topKeep = [];
for (const e of fs.readdirSync(rootNM, { withFileTypes: true })) {
  if (e.name.startsWith("@")) {
    const sc = path.join(rootNM, e.name);
    for (const s of fs.readdirSync(sc)) {
      const full = e.name + "/" + s;
      if (closure.has(full)) topKeep.push(full);
      else topDelete.push(path.join(sc, s));
    }
  } else {
    if (closure.has(e.name)) topKeep.push(e.name);
    else topDelete.push(path.join(rootNM, e.name));
  }
}

// 3b. 扩展自带 node_modules：同样只删闭包外的，**扩展代码目录绝不删**
const extNMDelete = [];
const extNMKeep = [];
if (fs.existsSync(extRoot)) {
  for (const id of fs.readdirSync(extRoot)) {
    const nm = path.join(extRoot, id, "node_modules");
    if (!fs.existsSync(nm)) continue;
    for (const e of fs.readdirSync(nm, { withFileTypes: true })) {
      if (e.name.startsWith("@")) {
        const sc = path.join(nm, e.name);
        for (const s of fs.readdirSync(sc)) {
          const full = e.name + "/" + s;
          if (closure.has(full)) extNMKeep.push(id + ":" + full);
          else extNMDelete.push(path.join(sc, s));
        }
      } else {
        if (closure.has(e.name)) extNMKeep.push(id + ":" + e.name);
        else extNMDelete.push(path.join(nm, e.name));
      }
    }
  }
}

// 3c. 【关键】闭包内包自己的嵌套 node_modules 一律保留。
// 为什么：包内的 require 可能不在 package.json 的 dependencies 里（
// chalk 4 的 require('supports-color') 就是例子），按声明算会漏。
// 嵌套 node_modules 体积很小，留着不心疼，删错就是起不来。
const nestedKeep = [];
function keepNested(dir) {
  const nested = path.join(dir, "node_modules");
  if (fs.existsSync(nested)) nestedKeep.push(nested);
}
for (const name of closure) {
  const d = findPkgDir(name);
  if (d) keepNested(d);
}
// 顶层保留包的顶层 node_modules（@scope/* 已经包含在内）
for (const name of topKeep) {
  const d = path.join(rootNM, ...name.split("/"));
  if (fs.existsSync(d)) keepNested(d);
}


const before = countFiles(ROOT);
let delFiles = 0;
for (const d of topDelete) delFiles += countFiles(d);
for (const d of extNMDelete) delFiles += countFiles(d);

console.log("=== 裁剪计划（判据：只删没被静态引用的第三方包，代码与元数据全留）===");
console.log("当前总文件: " + before);
console.log("静态引用包: " + requiredPkgs.size + " → 闭包 " + closure.size + " 个");
if (missing.length) console.log("⚠ 找不到的包（可选依赖，缺了不致命）: " + missing.length + " 个");
console.log("顶层 node_modules: 留 " + topKeep.length + " / 删 " + topDelete.length + " 个包");
console.log("扩展自带 node_modules: 留 " + extNMKeep.length + " / 删 " + extNMDelete.length + " 个包");
console.log("扩展代码目录: 全部保留（元数据缺一个就起不来，实测过）");
console.log("");
console.log("预计删除文件: " + delFiles);
console.log("裁剪后文件: " + (before - delFiles));
console.log("zip 65535 上限余量: " + (65535 - (before - delFiles)));

if (MODE !== "--apply") {
  console.log("\n（dry-run，未改动。加 --apply 执行）");
  fs.writeFileSync(
    path.join(ROOT, ".prune-plan.json"),
    JSON.stringify({ topDelete, extNMDelete, closure: [...closure] }, null, 2)
  );
  process.exit(0);
}

// ---------- 5. 执行 ----------
for (const d of topDelete) rmrf(d);
for (const d of extNMDelete) rmrf(d);
fs.rmSync(path.join(ROOT, ".prune-plan.json"), { force: true });
console.log("\n✓ 裁剪完成，删了 " + delFiles + " 个文件");
console.log("现在文件数: " + countFiles(ROOT));
