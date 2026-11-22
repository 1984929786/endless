#!/usr/bin/env node
/**
 * expand_nm_closure.js —— 从已有 .static-deps.json 出发，
 * 递归扫描「闭包内每个包」的实际 require/import，把缺失的补进闭包。
 *
 * 为什么需要它：
 *   package.json 的 dependencies 声明**不可靠**。chalk 4 内部
 *   `require('supports-color')`，但有的包把它标成 optional 或干脆漏写。
 *   只按声明算闭包 → 删掉实际需要的包 → 扩展加载失败（实测 43/85）。
 *
 * 判据：**代码里真写了 require/import 才算依赖**，不猜、不看声明。
 *
 * 用法：node expand_nm_closure.js <runtime目录> [--apply]
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
const APPLY = process.argv.includes("--apply");
if (!ROOT) {
  console.error("用法: node expand_nm_closure.js <runtime目录> [--apply]");
  process.exit(2);
}

const NODE_BUILTIN = new Set([
  "assert","async_hooks","buffer","child_process","cluster","console","constants",
  "crypto","dgram","diagnostics_channel","dns","domain","events","fs","http","http2",
  "https","inspector","module","net","os","path","perf_hooks","process","punycode",
  "querystring","readline","repl","stream","string_decoder","sys","timers","tls",
  "trace_events","tty","url","util","v8","vm","wasi","worker_threads","zlib",
]);

const REQ_RE = /(?:^|[\s;({=,])(?:require|import)\s*\(\s*['"]([^'"]+)['"]/g;
const IMP_RE = /(?:^|[\s;])(?:import|export)\s[^;'"]*?from\s*['"]([^'"]+)['"]/g;
const IMP_BARE_RE = /(?:^|[\n;])\s*import\s+['"]([^'"]+)['"]/g;

function pkgName(spec) {
  if (!spec) return null;
  if (spec.startsWith(".") || spec.startsWith("/") || spec.startsWith("#")) return null;
  if (spec.startsWith("node:")) return null;
  const base = spec.startsWith("@") ? spec.split("/").slice(0, 2).join("/") : spec.split("/")[0];
  if (NODE_BUILTIN.has(base) || base === "") return null;
  return base;
}

function walkFiles(dir, filter) {
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
      else if (e.isFile() && (!filter || filter(p))) out.push(p);
    }
  }
  return out;
}

// ---------- 读现有闭包 ----------
const nmDirs = [path.join(ROOT, "node_modules")];
const extRoot = path.join(ROOT, "dist", "extensions");
if (fs.existsSync(extRoot)) {
  for (const id of fs.readdirSync(extRoot)) {
    const nm = path.join(extRoot, id, "node_modules");
    if (fs.existsSync(nm)) nmDirs.push(nm);
  }
}

function findPkgDir(name) {
  for (const nm of nmDirs) {
    const d = path.join(nm, ...name.split("/"));
    if (fs.existsSync(d) && fs.existsSync(path.join(d, "package.json"))) return d;
  }
  return null;
}

let closure;
const planPath = path.join(ROOT, ".prune-plan.json");
if (fs.existsSync(planPath)) {
  closure = new Set(JSON.parse(fs.readFileSync(planPath, "utf8")).closure);
} else {
  // 从 .static-deps.json 重建
  const sd = JSON.parse(fs.readFileSync(path.join(ROOT, ".static-deps.json"), "utf8"));
  closure = new Set(Object.keys(sd));
}

// ---------- 递归扩展闭包 ----------
const scanned = new Set();
const queue = [...closure];
const added = [];
let rounds = 0;

while (queue.length && rounds < 20) {
  rounds++;
  const cur = queue.shift();
  if (scanned.has(cur)) continue;
  scanned.add(cur);
  const dir = findPkgDir(cur);
  if (!dir) continue;

  const jsFiles = walkFiles(
    dir,
    (p) => p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".cjs")
  );
  for (const f of jsFiles) {
    // 跳过测试/文档目录
    if (/[\\/](test|tests|__tests__|docs?|examples?)[\\/]/.test(f)) continue;
    let src;
    try {
      src = fs.readFileSync(f, "utf8");
    } catch (_) {
      continue;
    }
    if (src.length > 8 * 1024 * 1024) continue;
    for (const re of [REQ_RE, IMP_RE, IMP_BARE_RE]) {
      re.lastIndex = 0;
      let m;
      while ((m = re.exec(src)) !== null) {
        const p = pkgName(m[1]);
        if (!p) continue;
        if (!closure.has(p)) {
          closure.add(p);
          added.push(p);
          queue.push(p);
        }
      }
    }
  }
}

console.log("原有闭包: " + (closure.size - added.length) + " 个");
console.log("扫描包内代码新增: " + added.length + " 个");
if (added.length) {
  console.log("新增的（前 30）: " + added.slice(0, 30).join(", "));
}
console.log("闭包总计: " + closure.size + " 个");

if (!APPLY) {
  console.log("（未写回。加 --apply 更新 .prune-plan.json）");
  process.exit(0);
}

// 写回计划
let plan = { topDelete: [], extNMDelete: [], closure: [...closure] };
if (fs.existsSync(planPath)) plan = { ...plan, ...JSON.parse(fs.readFileSync(planPath, "utf8")) };
plan.closure = [...closure];
fs.writeFileSync(planPath, JSON.stringify(plan, null, 2));
console.log("✓ 已写回 .prune-plan.json");
