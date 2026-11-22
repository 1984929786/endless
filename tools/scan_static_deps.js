#!/usr/bin/env node
/**
 * scan_static_deps.js —— 扫描 dist/ 下所有 chunk 的顶层静态 import，
 * 找出引用了哪些第三方裸模块（非相对路径、非 node: 内置）。
 *
 * 为什么必须只认「顶层静态 import/export ... from」：
 *   - 动态 import("x") 只在运行时才解析，缺失不致命
 *   - 但静态 import 在模块加载期就解析，缺失 → 整个 chunk 加载失败 → 500
 *     （2026-11-22 的 @slack/web-api 事故就是这个）
 *
 * 用法：node scan_static_deps.js <runtime目录>
 * 输出：JSON { "包名": ["引用它的文件", ...] }
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
if (!ROOT) {
  console.error("用法: node scan_static_deps.js <runtime目录>");
  process.exit(2);
}

const NODE_BUILTIN = new Set([
  "assert","async_hooks","buffer","child_process","cluster","console","constants",
  "crypto","dgram","diagnostics_channel","dns","domain","events","fs","http","http2",
  "https","inspector","module","net","os","path","perf_hooks","process","punycode",
  "querystring","readline","repl","stream","string_decoder","sys","timers","tls",
  "trace_events","tty","url","util","v8","vm","wasi","worker_threads","zlib",
]);

// 只匹配语句开头的 import / export ... from（顶层静态），
// 允许前面有空白和分号。刻意不匹配 import( 动态形式。
const STATIC_IMPORT_RE = /(?:^|[\n;])\s*import\s+(?:[^;'"]*?\s+from\s+)?['"]([^'"]+)['"]/g;
const STATIC_EXPORT_RE = /(?:^|[\n;])\s*export\s+[^;'"]*?\s+from\s+['"]([^'"]+)['"]/g;
// bare specifier 的 re-import（bundler 产物里常见）
const REQUIRE_RE = /(?:^|[\n;])\s*(?:const|let|var)\s+[^=]+=\s*require\(\s*['"]([^'"]+)['"]\s*\)/g;

function isBare(spec) {
  if (!spec) return false;
  if (spec.startsWith(".") || spec.startsWith("/")) return false;
  if (spec.startsWith("node:")) return false;
  const base = spec.startsWith("@") ? spec.split("/").slice(0, 2).join("/") : spec.split("/")[0];
  if (NODE_BUILTIN.has(base)) return false;
  return true;
}

function pkgName(spec) {
  return spec.startsWith("@") ? spec.split("/").slice(0, 2).join("/") : spec.split("/")[0];
}

function walk(dir, out) {
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
}

const distDir = path.join(ROOT, "dist");
if (!fs.existsSync(distDir)) {
  console.error("✗ 找不到 " + distDir);
  process.exit(2);
}

const files = [];
walk(distDir, files);
const jsFiles = files.filter((f) => f.endsWith(".js") || f.endsWith(".mjs"));

const hits = new Map(); // pkg -> Set(file)
for (const f of jsFiles) {
  let src;
  try {
    src = fs.readFileSync(f, "utf8");
  } catch (_) {
    continue;
  }
  // 快速跳过：没有 import 字样就不用正则
  if (!src.includes("import") && !src.includes("require(")) continue;

  const rel = path.relative(ROOT, f);
  // 【关键判据】dist/extensions/** 是扩展代码，只有被启用时才加载；
  // 它们的静态 import 不能当“必须存在”的依据（否则会把 87 个禁用扩展的依赖全算进来，
  // 那就是 2026-11-22 那次把条目数推到 8.9 万的错）。
  // 只有 dist/ 根下的 chunk 才无条件加载。
  if (rel.startsWith("dist/extensions" + path.sep) || rel.startsWith("dist/extensions/")) continue;
  for (const re of [STATIC_IMPORT_RE, STATIC_EXPORT_RE, REQUIRE_RE]) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(src)) !== null) {
      const spec = m[1];
      if (!isBare(spec)) continue;
      const p = pkgName(spec);
      if (!hits.has(p)) hits.set(p, new Set());
      hits.get(p).add(rel);
    }
  }
}

// 输出：按引用次数排序
const result = {};
const entries = [...hits.entries()].sort((a, b) => b[1].size - a[1].size);
for (const [p, set] of entries) result[p] = [...set];

const outPath = path.join(ROOT, ".static-deps.json");
fs.writeFileSync(outPath, JSON.stringify(result, null, 2));
console.log("扫描 " + jsFiles.length + " 个 JS 文件");
console.log("被静态引用的第三方包: " + entries.length + " 个");
for (const [p, set] of entries.slice(0, 40)) {
  console.log("  " + String(set.size).padStart(4) + "  " + p);
}
console.log("明细已写入 " + outPath);
