// jsstrings.js —— 手写 JS 词法扫描：只返回「字符串字面量」的区间，不依赖任何第三方库。
//
// 【为什么要自己写（2026-11-23）】
// ununderscore.js 原来用纯文本替换，把 `function _instanceof()` 这种代码标识符
// 也当成路径段改了，导致 `Export 'AAA_instanceof' is not defined`，整个 ESM 链崩。
// 想用 acorn（AST 最稳），但 CI 里 acorn 不在 node_modules —— openclaw 并不直接依赖它，
// 只是本机恰好有。为一个改名脚本去 npm install acorn = 给构建加新依赖 = 新的失败点。
//
// 所以自己写一个状态机：code / string / template / lineComment / blockComment。
// 只在 string 和 template 内容里允许替换。代码区和注释区一律跳过。
//
// 【已知边界（写清楚，不假装完备）】
// - 正则字面量不单独识别：`/a"b/` 里的引号会被误判为字符串开头。
//   代价：可能把正则里的内容当字符串扫描。JS 里含引号的正则极少，
//   且我们只在字符串里找「/_name」形态，误伤概率极低。
//   这与 kt_precheck 的状态机是同一套取舍，已验证足够。
// - 模板字符串里的 ${...} 表达式不递归解析：${} 内部按字符串内容处理。
//   代价：`${_vendor}` 这种插值标识符本来就不该改，但会被当成字符串内容。
//   所以额外加一道保护：模板内容里出现 ${ 到 } 的片段整段跳过。

'use strict';

/**
 * 扫描 JS 源码，返回所有「字符串内容」区间 [{start, end}]。
 * start/end 指向字符串内容的第一个字符与最后一个字符之后（不含引号本身）。
 */
function stringRanges(src) {
  const out = [];
  let i = 0;
  const n = src.length;
  let state = 'code'; // code | sq | dq | tpl | line | block
  let start = 0;
  let braceDepth = 0; // 模板 ${} 深度

  while (i < n) {
    const c = src[i];
    const c2 = src[i + 1];

    if (state === 'code') {
      if (c === '/' && c2 === '/') { state = 'line'; i += 2; continue; }
      if (c === '/' && c2 === '*') { state = 'block'; i += 2; continue; }
      if (c === '"') { state = 'dq'; start = i + 1; i++; continue; }
      if (c === "'") { state = 'sq'; start = i + 1; i++; continue; }
      if (c === '`') { state = 'tpl'; start = i + 1; i++; continue; }
      i++;
      continue;
    }

    if (state === 'line') {
      if (c === '\n') { state = 'code'; }
      i++;
      continue;
    }

    if (state === 'block') {
      if (c === '*' && c2 === '/') { state = 'code'; i += 2; continue; }
      i++;
      continue;
    }

    // 字符串内部：处理转义
    if (c === '\\') { i += 2; continue; }

    if (state === 'dq' || state === 'sq') {
      const q = state === 'dq' ? '"' : "'";
      if (c === q) {
        out.push({ start, end: i });
        state = 'code';
        i++;
        continue;
      }
      i++;
      continue;
    }

    if (state === 'tpl') {
      // ${ 表达式：整段跳过，避免把插值里的标识符当字符串内容
      if (c === '$' && c2 === '{') {
        // 结束当前字符串段
        out.push({ start, end: i });
        braceDepth = 1;
        i += 2;
        // 跳过 ${...} 内部（简化：数花括号，忽略其中的字符串）
        while (i < n && braceDepth > 0) {
          const d = src[i];
          if (d === '\\') { i += 2; continue; }
          if (d === '"' || d === "'" || d === '`') {
            const qq = d;
            i++;
            while (i < n && src[i] !== qq) { if (src[i] === '\\') i++; i++; }
            i++;
            continue;
          }
          if (d === '{') braceDepth++;
          else if (d === '}') braceDepth--;
          i++;
        }
        start = i; // 表达式之后的模板内容从这里开始
        continue;
      }
      if (c === '`') {
        out.push({ start, end: i });
        state = 'code';
        i++;
        continue;
      }
      i++;
      continue;
    }
  }
  // 未闭合的字符串：忽略（源码本身有问题，不该由这里负责）
  return out;
}

module.exports = { stringRanges };
