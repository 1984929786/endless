// aapt 的 assets 过滤���则。
//
// ⚠️ 核心认知（2026-11-22 连续踩坑三次才定下来，别再"优化"这一段）：
//
//   `ignoreAssetsPattern` 里的 **`!xxx` 只能把内建默认规则「减掉」，不能新增模式**。
//   而能减掉哪些，AGP 的实现只认这几条内建规则名。
//   内建默认丢弃集：.svn .git .ds_store CVS thumbs.db picasa.ini *~ *.scc .* _*
//
//   实践结论（最稳的写法）：
//   - 想要 `_vendor/` `_generated_/` `_stream_readable.js` 进包 → 必须写 `!_*`
//   - 想要 `.github/` `.i18n/` `.buildstamp` 进包 → 必须写 `!.*`
//   - 但一旦 `!.*` 生效，`.svn/` `.git/` `.npmignore` `.travis.yml` 也会一起进包
//     （因为 `.*` 被打掉了）。这是**必须付的代价**，因为这些是别人的 npm 包，
//     我们无法改名也无法单独过滤 —— 宁可 APK 多几 MB，也不能让运行时报缺文件。
//
//   所以最终判断逻辑就是三条：
//     1. 计算「仍生效的默认丢弃规则」= DEFAULTS - (!x 里与默认规则名相同的那些)
//     2. 再加上 pattern 里不带 ! 的额外丢弃规则
//     3. 文件中任意一段（basename 或目录名）命中 → 丢
//
// 本文件是唯一真源：make_manifest.js 用它生成清单，apk_verify.js 用它校验包。

const DEFAULTS = ['.svn', '.git', '.ds_store', 'CVS', 'thumbs.db', 'picasa.ini',
  '*~', '*.scc', '.*', '_*'];

/** fnmatch → RegExp（`*` 不跨 '/'，`?` 单字符） */
function globToRe(g) {
  let r = '';
  for (const ch of g) {
    if (ch === '*') r += '[^/]*';
    else if (ch === '?') r += '[^/]';
    else if ('.+^$(){}|[]\\'.includes(ch)) r += '\\' + ch;
    else r += ch;
  }
  return new RegExp('^' + r + '$');
}

function parsePattern(pattern) {
  const keep = [], drop = [];
  for (const t of String(pattern || '').split(':')) {
    if (!t) continue;
    if (t[0] === '!') keep.push(t.slice(1)); else drop.push(t);
  }
  return { keep, drop };
}

/** 返回仍生效的丢弃正则列表 */
function dropRules(pattern) {
  const { keep, drop } = parsePattern(pattern);
  const removed = new Set(keep.filter(k => DEFAULTS.includes(k)));
  const out = [];
  for (const d of DEFAULTS) if (!removed.has(d)) out.push(d);
  for (const d of drop) if (!removed.has(d)) out.push(d);
  return out.map(globToRe);
}

/** rel 会被 aapt 丢弃吗 */
function isDropped(rel, pattern) {
  const rules = dropRules(pattern);
  const segs = rel.split('/');
  // 目录段也要比：命中 `_*` 的目录会整棵子树消失
  return rules.some(re => segs.some(s => re.test(s)));
}

module.exports = { DEFAULTS, globToRe, parsePattern, dropRules, isDropped };

if (require.main === module) {
  const fs = require('fs');
  const gradle = process.argv[2] || 'app/app/build.gradle';
  const m = fs.readFileSync(gradle, 'utf8').match(/ignoreAssetsPattern\s+'([^']*)'/);
  if (!m) { console.log('✗ 找不到 ignoreAssetsPattern'); process.exit(1); }
  const p = m[1];
  console.log('pattern:', p);
  console.log('仍生效的丢弃规则:', dropRules(p).map(x => x.source).join(' ') || '(无)');
  // 断言：项目真正依赖的运行时文件必须全部保留
  const mustKeep = [
    'node_modules/openai/_vendor/zod-to-json-schema/index.js',
    'node_modules/openai/src/_vendor/zod-to-json-schema/index.ts',
    'dist/extensions/discord/node_modules/@buape/carbon/node_modules/discord-api-types/_generated_/rest/v10/interfaces.js',
    'dist/extensions/discord/node_modules/@buape/carbon/node_modules/discord-api-types/payloads/v10/_interactions/_applicationCommands/_chatInput/base.js',
    'node_modules/openai/node_modules/.bin/openai',
    'dist/entry.js',
    'openclaw.mjs'
  ];
  let bad = 0;
  for (const f of mustKeep) {
    if (isDropped(f, p)) { bad++; console.log('✗ 会被丢弃: ' + f); }
  }
  console.log(bad ? '✗ ' + bad + ' 个必需文件会被丢' : '✓ 必需文件全部保留');
  process.exit(bad ? 1 : 0);
}
