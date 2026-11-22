// check_release_rules.js —— 释放/校验判据的机器检查
//
// 【为什么存在】2026-11-22 连续踩坑：
//   1. 用 `dest.length() > 0` 判断「已释放」→ 零字节文件每次重写
//   2. 用 `f.length() == 0L` 判断「缺失」→ 23 个合法零字节文件被当成缺文件，
//      释放明明成功却报「释放后缺 23 个文件」，反复误导排查方向
//   判据错了，后面所有推断都是错的。所以把判据做成可执行的检查。
//
// 用法：node tools/check_release_rules.js app/app/src/main/java/com/xundao/yongwuzhijing/GatewayLauncher.kt
// 退出码 0 = 通过，1 = 违规

const fs = require('fs');

const file = process.argv[2];
if (!file) { console.log('用法: node check_release_rules.js <GatewayLauncher.kt>'); process.exit(2); }
const src = fs.readFileSync(file, 'utf8');

let bad = 0;
function fail(msg, line) {
  console.log('✗ ' + msg + (line ? '  (第 ' + line + ' 行)' : ''));
  bad++;
}

// 规则 1：清单校验里不许用 length()==0 判定缺失
const lines = src.split('\n');
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  if (l.trim().startsWith('//') || l.trim().startsWith('*')) continue;
  // 在 verifyManifest / extractByManifest 函数体内
  if (/\.length\(\)\s*==\s*0L?/.test(l) || /\.length\(\)\s*<=\s*0/.test(l)) {
    fail('用 length()==0 判断缺失（零字节文件是合法的）', i + 1);
  }
  if (/exists\(\)\s*&&\s*\w+\.length\(\)\s*>\s*0/.test(l)) {
    fail('用 exists() && length()>0 判断已释放（应只用 isFile）', i + 1);
  }
}

// 规则 2：清单校验必须用 isFile，不许用 exists（exists 对目录也返回 true）
const verifyBody = src.slice(src.indexOf('private fun verifyManifest'), src.indexOf('private fun ensureSymlinks'));
if (verifyBody && !/f\.isFile/.test(verifyBody)) {
  fail('verifyManifest 里没有用 f.isFile');
}
if (verifyBody && /!f\.exists\(\)/.test(verifyBody)) {
  fail('verifyManifest 用 exists() 判断，目录也会通过');
}

// 规则 3：释放循环必须用 isFile 判断跳过
const extractBody = src.slice(src.indexOf('private fun extractByManifest'), src.indexOf('private fun verifyManifest'));
if (extractBody && !/dest\.isFile/.test(extractBody)) {
  fail('extractByManifest 里没有用 dest.isFile 判断已释放');
}

if (bad) { console.log('\n共 ' + bad + ' 处违规'); process.exit(1); }
console.log('✓ 释放/校验判据检查通过');
