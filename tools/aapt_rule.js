// aapt 的 assets 过滤规则。
//
// ⚠️ 核心认知（2026-11-22 实测定论，与之前所有推测相反，请勿再改）：
//
//   **`ignoreAssetsPattern` 里的 `!xxx` 放行写法对我们这个场景完全无效。**
//
//   实测证据（不是推测）：
//     build.gradle 写的是 '!_*:!.*:!.github:...'
//     但 3.4.0 真实包内：以 '_' 开头的路径段 = 0 个，以 '.' 开头的路径段 = 0 个。
//     如果 `!_*` / `!.*` 真能放行，这两个数不可能是 0。
//
//   所以内建默认丢弃集始终生效：
//     .svn  .git  .ds_store  CVS  thumbs.db  picasa.ini  *~  *.scc  .*  _*
//   凡是以 '.' 或 '_' 开头的路径段，都会被整棵子树丢弃。
//
//   真正的对策不在打包器配置，而在「让文件不触发规则」：
//     tools/ununderscore.js 把 `_vendor` 改名为 `AAA_vendor` 并改写引用。
//     点开头的文件基本是 npm 配置（.npmignore/.gitignore），运行时不读，CI 里直接删。
//
//   本文件现在的职责：用**真实生效**的丢弃集生成清单，让清单与包内容一致。

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

/**
 * 返回生效的丢弃正则列表。
 * 【2026-11-22】实测结论：keep（!xxx）无效，一律不参与减法。
 * 早期版本把 `!x` 当作「从 DEFAULTS 里减掉」，导致清单以为 _vendor 会进包，
 * 而实际没进，两边永远对不上 —— 这也是清单一直「看起来一致」却启动失败的原因之一。
 */
function dropRules(pattern) {
  const { drop } = parsePattern(pattern);
  const out = [...DEFAULTS];
  for (const d of drop) if (!out.includes(d)) out.push(d);
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
  console.log('生效的丢弃规则:', dropRules(p).map(x => x.source).join(' ') || '(无)');
  // 断言：改名之后，运行时依赖的文件必须都不触发规则
  const mustKeep = [
    'node_modules/openai/AAA_vendor/zod-to-json-schema/index.js',
    'node_modules/@anthropic-ai/sdk/AAA_vendor/partial-json-parser/parser.mjs',
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
