// kt_refs.js —— 检查 Kotlin 文件里"用了但没定义"的常量
//
// 为什么要有这个：2026-11-24 我写脚本替换 GatewayLauncher.kt 的 DEFAULT_MODEL 段时，
// 顺手把同段的 PREF_KEY 常量也删了。本地预检（只查注释/字符串边界）全绿，
// CI 编译才炸。这类"删掉了别人还在用的东西"的错，必须是脚本级检查，不能靠记性。
//
// 判据演进（两次红样本才修对，记下来）：
// 1. 第一版把所有大写单词都当"引用"，定义处也算 → 删了定义仍判绿。
// 2. 第二版抹掉定义，但 strip 不认 `${}` 里的嵌套字符串 →
//    日期格式 "MM-dd HH:mm:ss" 漏进 code 区，误报 MM/HH。
// 最后改成带状态栈的 strip：${...} 内部递归按 code 处理。
//
// 用法: sh tools/jn tools/kt_refs.js <目录或文件> [...]

const fs = require('fs');
const path = require('path');

function walk(p, out) {
  const st = fs.statSync(p);
  if (st.isDirectory()) {
    for (const f of fs.readdirSync(p)) walk(path.join(p, f), out);
  } else if (/\.kt$/.test(p)) {
    out.push(p);
  }
}

// 去掉注释和字符串内容，但 ${...} 模板内部按代码处理
function strip(src) {
  let o = '';
  let i = 0;
  const stack = [];
  let state = 'code';

  while (i < src.length) {
    const c = src[i], n = src[i + 1];

    if (state === 'line') {
      if (c === '\n') { state = stack.pop() || 'code'; o += c; }
      i++; continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') { state = stack.pop() || 'code'; i += 2; }
      else i++;
      continue;
    }
    if (state === 'str' || state === 'raw') {
      if (state === 'str' && c === '\\') { i += 2; continue; }
      if (state === 'str' && c === '"') { state = stack.pop() || 'code'; i++; continue; }
      if (state === 'raw' && src.slice(i, i + 3) === '"""') { state = stack.pop() || 'code'; i += 3; continue; }
      if (c === '$' && n === '{') {
        stack.push(state);
        state = 'code';
        o += '${';
        i += 2;
        continue;
      }
      i++; continue;
    }
    if (state === 'chr') {
      if (c === '\\') { i += 2; continue; }
      if (c === "'") { state = stack.pop() || 'code'; i++; }
      i++; continue;
    }

    // code
    if (c === '/' && n === '/') { stack.push('code'); state = 'line'; i += 2; continue; }
    if (c === '/' && n === '*') { stack.push('code'); state = 'block'; i += 2; continue; }
    if (c === '"') {
      if (src.slice(i, i + 3) === '"""') { stack.push('code'); state = 'raw'; i += 3; }
      else { stack.push('code'); state = 'str'; i++; o += '""'; }
      continue;
    }
    if (c === "'") { stack.push('code'); state = 'chr'; i++; o += "''"; continue; }
    if (c === '}' && stack.length && (stack[stack.length - 1] === 'str' || stack[stack.length - 1] === 'raw')) {
      state = stack.pop();
      o += '}';
      i++;
      continue;
    }
    o += c; i++;
  }
  return o;
}

const DEF_PATTERNS = [
  /\b(?:private|public|internal|protected)?\s*const\s+val\s+([A-Za-z_]\w*)/g,
  /\b(?:private|public|internal|protected)?\s*(?:val|var)\s+([A-Za-z_]\w*)/g,
  /\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(/g,
  /\b(?:class|object|interface|typealias)\s+([A-Za-z_]\w*)/g,
];

function collectDefs(code) {
  const names = new Set();
  for (const re of DEF_PATTERNS) {
    let m;
    re.lastIndex = 0;
    while ((m = re.exec(code))) names.add(m[1]);
  }
  return names;
}

function blankDefs(code, names) {
  let out = code;
  for (const n of names) {
    out = out.replace(
      new RegExp('((?:const\\s+)?(?:val|var)\\s+)' + n + '\\b', 'g'),
      (m0, pre) => pre + '\u0000'
    );
    out = out.replace(
      new RegExp('((?:fun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z_][\\w.]*\\.)?)|(?:class\\s+)|(?:object\\s+)|(?:interface\\s+)|(?:typealias\\s+))' + n + '\\b', 'g'),
      (m0, pre) => pre + '\u0000'
    );
  }
  return out;
}

// Android / Java 平台常量：不是本仓库定义的，但合法
const PLATFORM = /^(TAG|NONE|TRUE|FALSE|NULL|OK|UTF_8|ASCII|MAX_VALUE|MIN_VALUE|TYPE|VERSION|URL|START_STICKY|START_NOT_STICKY|START_REDELIVER_INTENT|MODE_PRIVATE|MODE_APPEND|MODE_MULTI_PROCESS|FIELD|METHOD|ALARM_SERVICE|CONNECTIVITY_SERVICE|ACTION_VIEW|ACTION_SEND|ACTION_MAIN|EXTRA_TEXT|BUNDLE|INTENT|NOTIFICATION_SERVICE|WINDOW_SERVICE)$/;

function main() {
  const args = process.argv.slice(2);
  if (!args.length) { console.log('用法: kt_refs.js <目录或文件> [...]'); process.exit(2); }

  const files = [];
  for (const a of args) walk(a, files);

  const stripped = new Map();
  const allDefs = new Set();
  for (const f of files) {
    const code = strip(fs.readFileSync(f, 'utf8'));
    stripped.set(f, code);
    for (const d of collectDefs(code)) allDefs.add(d);
  }

  const blanked = [...stripped.entries()].map(([f, c]) => [f, blankDefs(c, allDefs)]);

  let bad = 0;
  for (const [f, code] of blanked) {
    const refs = new Map();  // name -> 首次位置
    let m;
    const reUpper = /\b([A-Z][A-Z0-9_]{1,})\b/g;
    while ((m = reUpper.exec(code))) {
      if (!refs.has(m[1])) refs.set(m[1], m.index);
    }

    for (const [r, at] of refs) {
      if (PLATFORM.test(r)) continue;
      if (allDefs.has(r)) continue;
      if (at > 0 && (code[at - 1] === '.' || code[at - 1] === '\u0000')) continue;
      console.log('未定义常量: ' + f + ' -> ' + r);
      bad++;
    }
  }

  if (bad === 0) {
    console.log('kt_refs: 无未定义常量（' + files.length + ' 文件）');
    process.exit(0);
  }
  console.log('kt_refs: ' + bad + ' 处可疑');
  process.exit(1);
}

main();
