#!/usr/bin/env node
/**
 * strip_nonruntime.js —— 删除运行时绝对用不到的文件，绕开 zip 65535 条目上限。
 *
 * ===== 为什么是这个脚本 =====
 * zip 单文件硬上限 65535 条目（APK 也是 zip，AGP 的 apkzlib 写死这个数）。
 * 全量运行时 85856 个文件 → 撞上限 → packageDebug 失败：Too many zip entries 89534。
 *
 * 试过但被实测打回的两条路：
 *   A. ZIP64        —— apksigner 报 ApkFormatException，AOSP 安装器零 ZIP64 代码，不可行
 *   B. 裁掉禁用扩展的依赖 —— 实测 `plugins list` 崩：chalk 4 内部 require('supports-color')
 *                      不在 dependencies 声明里，删了一个都起不来。**删依赖 = 赌博。**
 *
 * 现行判据：只删「运行时绝对用不到」的产物，一个依赖都不碰。
 *   - *.map / *.d.ts / *.d.mts / *.d.cts  调试与类型声明
 *   - *.ts（非 .d.ts）                    源码，跑的是编译后的 .js
 *   - test/ tests/ __tests__/ 目录        测试代码
 *   - 非 README/LICENSE/CHANGELOG 的 .md  文档
 *
 * 判据安全性的依据：这些都是**开发期产物**，Node 运行时只加载 .js/.json/.node。
 * 极少数包会在运行时读自己的 .md（如 readme 展示），实测本运行时无此情况。
 *
 * 用法：node strip_nonruntime.js <runtime目录> [--dry-run|--apply]
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
const MODE = process.argv[3] || "--dry-run";
if (!ROOT) {
  console.error("用法: node strip_nonruntime.js <runtime目录> [--dry-run|--apply]");
  process.exit(2);
}

const ZIP_ENTRY_LIMIT = 65535;

const TEST_DIRS = new Set(["test", "tests", "__tests__", "__mocks__", "e2e", "spec"]);
const KEEP_MD = /^(readme|license|licence|changelog|notice|authors|contributing)/i;

// 【2026-11-26 事故】按扩展名删 .md 把 docs/reference/templates/ 下的
// AGENTS.md / SOUL.md / TOOLS.md … 全删了 → 运行时抛出
// Error: Missing workspace template: AGENTS.md → OpenAI 兼容端点 500（114ms，模型都没调到）。
// 教训：裁剪规则不能只看扩展名，要看「这个目录是不是运行时资产目录」。
// 这里白名单化：templates 目录下的 .md 一律保留。
const KEEP_MD_DIRS = ["docs/reference/templates", "templates"];
function isRuntimeAssetDir(rel) {
  return KEEP_MD_DIRS.some((d) => rel.includes(d));
}

function isNonRuntime(rel) {
  const parts = rel.split("/");
  const base = parts[parts.length - 1];

  if (base.endsWith(".map")) return "sourcemap";
  if (base.endsWith(".d.ts") || base.endsWith(".d.mts") || base.endsWith(".d.cts")) return "dts";
  if (base.endsWith(".ts") || base.endsWith(".tsx")) return "src-ts";
  if (base.endsWith(".md")) {
    if (KEEP_MD.test(base)) return null;
    if (isRuntimeAssetDir(rel)) return null;   // ← 运行时资产目录，不能删
    return "docs";
  }

  // 目录名命中测试目录（不含顶层 dist/extensions 自身）
  for (let i = 0; i < parts.length - 1; i++) {
    if (TEST_DIRS.has(parts[i].toLowerCase())) return "test-dir";
  }
  return null;
}

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

const all = walkFiles(ROOT);
const byReason = {};
const kill = [];
for (const f of all) {
  const rel = path.relative(ROOT, f).split(path.sep).join("/");
  const reason = isNonRuntime(rel);
  if (reason) {
    byReason[reason] = (byReason[reason] || 0) + 1;
    kill.push(f);
  }
}

const after = all.length - kill.length;
console.log("=== 裁剪非运行时产物 ===");
console.log("当前文件: " + all.length);
for (const [k, v] of Object.entries(byReason).sort((a, b) => b[1] - a[1])) {
  console.log("  " + k.padEnd(12) + v);
}
console.log("待删: " + kill.length);
console.log("裁剪后: " + after);
console.log("zip 上限 " + ZIP_ENTRY_LIMIT + "，余量: " + (ZIP_ENTRY_LIMIT - after));

if (after >= ZIP_ENTRY_LIMIT) {
  console.log("");
  console.log("⚠ 仍然超过上限，需要额外裁剪策略");
}

if (MODE !== "--apply") {
  console.log("\n（dry-run，未改动。加 --apply 执行）");
  process.exit(0);
}

if (after >= ZIP_ENTRY_LIMIT) {
  console.log("\n✗ 拒绝执行：删完仍然超上限，说明判据不够，别白删");
  process.exit(1);
}

let n = 0;
for (const f of kill) {
  try {
    fs.unlinkSync(f);
    n++;
  } catch (_) {}
}
// 清空目录
const dirs = new Set();
for (const f of kill) dirs.add(path.dirname(f));
for (const d of [...dirs].sort((a, b) => b.length - a.length)) {
  try {
    const rest = fs.readdirSync(d);
    if (rest.length === 0) fs.rmdirSync(d);
  } catch (_) {}
}

console.log("\n✓ 删除 " + n + " 个文件");
console.log("现在文件数: " + walkFiles(ROOT).length);
