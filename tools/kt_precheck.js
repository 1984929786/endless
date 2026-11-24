// Kotlin 源码预检：推送前必跑，避免浪费一轮 CI
// 用法： sh tools/jn tools/kt_precheck.js <目录或文件...>
//
// 检查项（都是真实踩过的坑）：
// 1. 块注释 /* 与 */ 数量不平衡 —— Kotlin 块注释支持嵌套，注释正文里写 "a/*.ext" 会开启新注释
// 2. 花括号深度最终不为 0
// 3. 引用了已删除的 Cactus 类
//
// 为什么用状态机：注释里的撇号（Arm's）会被误当字符字面量开头，
// 一路吞掉后面的 */ 和 /**，导致计数错乱。必须按状态区分。
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
if (!args.length) {
  console.error('用法: kt_precheck.js <目录或文件...>');
  process.exit(2);
}

// 状态机扫描：返回 {commentErrors, braceDepth, negatives, codeLines}
// 同时抹掉字符串与注释内容，方便后续查符号
function scan(src) {
  let i = 0;
  let line = 1;
  let commentDepth = 0;
  let braceDepth = 0;
  let firstNest = -1;
  let negLine = -1;
  const code = [];
  let mode = 'code';   // code | block | line | dq | tdq | sq

  while (i < src.length) {
    const c = src[i], n = src[i + 1];

    if (c === '\n') line++;

    if (mode === 'line') {
      if (c === '\n') { mode = 'code'; code.push('\n'); }
      else code.push(' ');
      i++;
      continue;
    }

    if (mode === 'block') {
      if (c === '/' && n === '*') {
        commentDepth++;
        if (commentDepth === 2 && firstNest < 0) firstNest = line;
        i += 2;
        continue;
      }
      if (c === '*' && n === '/') {
        commentDepth--;
        if (commentDepth === 0) mode = 'code';
        i += 2;
        continue;
      }
      code.push(c === '\n' ? '\n' : ' ');
      i++;
      continue;
    }

    if (mode === 'dq') {
      if (c === '\\') { code.push('  '); i += 2; continue; }
      if (c === '"') { mode = 'code'; code.push(' '); i++; continue; }
      code.push(c === '\n' ? '\n' : ' ');
      i++;
      continue;
    }

    if (mode === 'tdq') {
      if (src.slice(i, i + 3) === '"""') { mode = 'code'; code.push('   '); i += 3; continue; }
      code.push(c === '\n' ? '\n' : ' ');
      i++;
      continue;
    }

    if (mode === 'sq') {
      if (c === '\\') { code.push('  '); i += 2; continue; }
      if (c === "'") { mode = 'code'; code.push(' '); i++; continue; }
      code.push(c === '\n' ? '\n' : ' ');
      i++;
      continue;
    }

    // mode === 'code'
    if (c === '/' && n === '*') { mode = 'block'; commentDepth = 1; i += 2; continue; }
    if (c === '/' && n === '/') { mode = 'line'; i += 2; continue; }
    if (c === '"' && src.slice(i, i + 3) === '"""') { mode = 'tdq'; i += 3; continue; }
    if (c === '"') { mode = 'dq'; code.push(' '); i++; continue; }
    if (c === "'") { mode = 'sq'; code.push(' '); i++; continue; }
    if (c === '{') braceDepth++;
    if (c === '}') { braceDepth--; if (braceDepth < 0 && negLine < 0) negLine = line; }
    code.push(c);
    i++;
  }

  return {
    unclosedComment: mode === 'block' && commentDepth > 0,
    commentDepth,
    firstNest,
    braceDepth,
    negLine,
    code: code.join(''),
  };
}

function walk(dir, out) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (name.endsWith('.kt')) out.push(p);
  }
}

const allFiles = [];
for (const a of args) {
  if (!fs.existsSync(a)) { console.log('跳过不存在的路径:', a); continue; }
  if (fs.statSync(a).isDirectory()) walk(a, allFiles);
  else if (a.endsWith('.kt')) allFiles.push(a);
}

let errors = 0;

for (const f of allFiles) {
  const src = fs.readFileSync(f, 'utf8');
  const rel = path.relative(process.cwd(), f);
  const r = scan(src);

  if (r.unclosedComment) {
    console.log(`✗ ${rel}: 块注释未闭合（剩余深度 ${r.commentDepth}）` +
      (r.firstNest > 0 ? `  最早嵌套于第 ${r.firstNest} 行` : ''));
    errors++;
  }

  if (r.braceDepth !== 0) {
    console.log(`✗ ${rel}: 花括号最终深度 ${r.braceDepth}` +
      (r.negLine > 0 ? `  首次为负在第 ${r.negLine} 行` : ''));
    errors++;
  }

  const BANNED = /\bCactusJNI\b|\bCactusEngine\b|\bCactusEngineHolder\b/;
  r.code.split('\n').forEach((l, i) => {
    if (BANNED.test(l)) {
      console.log(`✗ ${rel}:${i + 1} 仍引用已删除的 Cactus 类: ${l.trim().slice(0, 70)}`);
      errors++;
    }
  });
}

console.log(errors ? `\n预检失败：${errors} 处问题` : `预检通过：${allFiles.length} 个文件`);
process.exit(errors ? 1 : 0);
