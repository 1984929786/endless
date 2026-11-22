// 生成 openclaw runtime 的释放清单（assets/openclaw/manifest.txt）。
//
// 为什么需要这一步（2026-11-22 血案）：
//   上一版清单是 `find . -type f` 全量写的，而打包时 aapt 按
//   ignoreAssetsPattern 悄悄丢掉了 `_*` / `.*` 开头的文件与目录
//   （清单 52414 条、包内 51350 条），App 逐条校验永远失败。
//
// 现在：清单 = 「按 aapt 规则过滤后真正会进包的文件」。
// 规则来自 tools/aapt_rule.js（与 apk_verify.js 共用同一份实现，避免两边漂移）。
//
// 用法：node tools/make_manifest.js <runtime目录> <输出清单> <build.gradle>
const fs = require('fs');
const path = require('path');
const { isDropped, parsePattern } = require('./aapt_rule.js');

const [srcDir, outFile, gradleFile] = process.argv.slice(2);
if (!srcDir || !outFile || !gradleFile) {
  console.log('用法: make_manifest.js <src> <out> <build.gradle>');
  process.exit(2);
}

const g = fs.readFileSync(gradleFile, 'utf8');
const m = g.match(/ignoreAssetsPattern\s+'([^']*)'/);
if (!m) { console.log('✗ 在 ' + gradleFile + ' 里找不到 ignoreAssetsPattern'); process.exit(1); }
const pattern = m[1];

const out = [];
const skipped = [];
(function walk(dir, rel) {
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const r = rel ? rel + '/' + name : name;
    const st = fs.statSync(abs);
    // aapt 是「命中就整棵子树都不进包」，所以目录名命中时直接不进去
    if (isDropped(r, pattern)) { skipped.push(r); continue; }
    if (st.isDirectory()) walk(abs, r);
    else if (st.isFile()) out.push(r);
  }
})(srcDir, '');

out.sort();
fs.writeFileSync(outFile, out.join('\n') + '\n');
console.log('清单已生成：' + out.length + ' 条（跳过 ' + skipped.length + ' 条，规则 ' + pattern + '）');
if (skipped.length) {
  console.log('被跳过的顶层样本: ' + skipped.slice(0, 5).join(' | '));
}

// 自检：清单里绝不允许再出现会被丢的条目
const bad = out.filter(x => isDropped(x, pattern));
if (bad.length) {
  console.log('✗ 清单里仍有 ' + bad.length + ' 条会被 aapt 丢弃');
  for (const b of bad.slice(0, 5)) console.log('   ' + b);
  process.exit(1);
}
