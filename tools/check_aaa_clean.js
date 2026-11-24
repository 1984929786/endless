// check_aaa_clean.js —— 打包前的最后一道闸：目录里 JS 的代码区不许出现 AAA_xxx。
// 独立于 ununderscore.js 的内部自检（脚本自身坏掉时，这条仍然有效）。
// 用法: node tools/check_aaa_clean.js <runtime目录>
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
if (!root || !fs.existsSync(root)) { console.log('✗ 目录不存在: ' + root); process.exit(2); }

const { stringRanges } = require(path.join(__dirname, 'jsstrings.js'));
const PREFIX = 'AAA_';
const offenders = [];
let scanned = 0, skipped = 0;

(function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.lstatSync(abs);
    if (st.isDirectory()) { walk(abs); continue; }
    if (!st.isFile()) continue;
    if (!/\.(js|mjs|cjs)$/.test(name)) continue;
    if (st.size > 8 * 1024 * 1024) { skipped++; continue; }
    let src;
    try { src = fs.readFileSync(abs, 'utf8'); } catch (_) { skipped++; continue; }
    if (!src.includes(PREFIX)) continue;
    scanned++;
    let ranges;
    try { ranges = stringRanges(src); } catch (_) { ranges = []; }
    // 代码区出现 AAA_ 才算污染。属性名/标识符都在代码区，字符串不算。
    const re = new RegExp('(^|[^A-Za-z0-9_$])' + PREFIX + '[A-Za-z0-9_$]*', 'g');
    let m;
    while ((m = re.exec(src)) !== null) {
      const pos = m.index + m[1].length;
      if (ranges.some(r => pos >= r.start && pos < r.end)) continue;
      offenders.push(path.relative(root, abs) + ' :: ' + m[0].slice(m[1].length));
      break;
    }
    if (offenders.length > 40) break;
  }
})(root);

console.log(`扫描含 AAA_ 的 JS: ${scanned}，跳过(过大/读失败): ${skipped}`);
if (offenders.length) {
  console.log('✗ 代码区发现 ' + offenders.length + ' 处 AAA_（会导致 Export is not defined）：');
  for (const o of offenders.slice(0, 20)) console.log('   ' + o);
  process.exit(1);
}
console.log('✓ 代码区无 AAA_ 污染（字符串内的路径改名属正常）');
