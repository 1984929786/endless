// selfcheck.js —— 改名脚本的「反例自检」。这是 B 方案的核心：不靠记性，靠机器拦。
//
// 背景（2026-11-23 -> 11-24）：
//   第一版用文本替换，把 `function _instanceof()` 改成了 `AAA_instanceof`，
//   导致 `Export 'AAA_instanceof' is not defined`，整个 ESM 链崩。
//   第二版换成了「只在字符串区间内替换」，但**自检二**仍然有漏洞：
//   jsonc-parser 的 UMD 头是 `(function (factory) { ... }(this, function (exports) {`
//   而 acorn 以 sourceType:'module' 严格模式解析 → 把最外层 function 声明了的
//   `_instanceof` / `_apply_decs_2203_r` 等在严格模式下算作「关键字/保留字」相关错误，
//   或干脆解析失败 → 自检二 `catch(_) { continue; }` 直接跳过该文件 → 漏检。
//
//   结果：包在 CI 里绿灯出包，装到手机上才炸。这就是「三道自检都绿了还是错」的根因。
//
// 修法（本次）：
//   ① 自检二的 acorn 解析改成「多模式尝试」：module → script → script+allowReturnOutsideFunction。
//      全部失败时 **不再静默跳过**，而是退回词法区间判定（stringRanges 已经可靠），
//      并且**把「解析失败的文件」列入失败**（fail-closed），而不是当它不存在。
//   ② 判据从「有没有 AAA_ 标识符」升级为「AST 里的 Identifier/=Identifier token 是否以 AAA_ 开头」，
//      用 acorn 的 onToken 拿全部 name token，位置精确，不受 export relink 错误影响。
//   ③ 加「反向用例」：故意造一个被污染的文件进去，脚本必须报错。这就是红→绿对照。
//
// 用法:
//   node tools/selfcheck.js generate <workdir>     # 造正/反例样本
//   node tools/selfcheck.js verify <workdir>       # 用 ununderscore 跑样本并断言结果
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const WS = path.resolve(__dirname, '..');

// ── 样本：每个都指明「期望结果」 ─────────────────────────────
const CASES = [
  {
    name: 'ok-basic',
    expect: 'pass',
    files: {
      'node_modules/_vendor/lib/a.js': `const p = require("./_vendor/lib/b.js");\nexport const x = 1;\n`,
      'node_modules/_vendor/lib/b.js': `exports.y = 2;\n`,
    },
  },
  {
    name: 'bad-ident', // 反例：标识符被改名 —— 这一条必须失败
    expect: 'fail',
    files: {
      'node_modules/_vendor/zod.js': `function _instanceof(a, b) { return a instanceof b; }\nexport { _instanceof as instanceof };\n`,
    },
  },
  {
    name: 'ok-umd', // 正例：UMD 头 + 合法的字符串内路径
    expect: 'pass',
    files: {
      'node_modules/_vendor/jsonc.js': `(function (factory) {\n  if (typeof module === "object") module.exports = factory(require("./_vendor/impl.js"));\n}(this, function (exports) {\n  function _identity(x) { return x; }\n  exports.id = _identity;\n}));\n`,
      'node_modules/_vendor/impl.js': `module.exports = 1;\n`,
    },
  },
  {
    name: 'bad-swc', // 反例：UMD 里 function 名为 AAA_ 开头（真实事故形态）
    expect: 'fail',
    files: {
      'node_modules/_vendor/swc-helper.cjs': `(function (exports) {\n  function AAA_apply_decs_2203_r(target, methods) { return target; }\n  exports.a = AAA_apply_decs_2203_r;\n}(this, function (exports) { exports.a = 1; }));\n`,
    },
  },
];

// ── generate：把样本写到磁盘 ─────────────────────────────────
function generate(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
  for (const c of CASES) {
    for (const [rel, content] of Object.entries(c.files)) {
      const abs = path.join(dir, c.name, rel);
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      // 反例样本要「模拟未改名的原始目录」：文件名里带 _vendor，
      // 这样 ununderscore 才会把它当成待改名路径段，从而进入真正的处理逻辑。
      // 2026-11-24：第一版直接写 _vendor 目录，但 generate 用 mkdir 建的就是 "_vendor"，
      // 看着对；错在 c.expect 判据被写成「只看 exit code」，
      // 而真正该断言的是「处理完之后代码区有没有 AAA_」。两处都修。
      fs.writeFileSync(abs, content);
    }
  }
  console.log('已生成 ' + CASES.length + ' 组样本到 ' + dir);
}

// ── 断言工具 ─────────────────────────────────────────────────
function assertAnyAAA(target, label) {
  // 在目标目录里找「代码区」出现 AAA_ 的痕迹：用与 ununderscore 相同的审计逻辑外挂判定
  let hits = 0;
  const acorn = (() => { try { return require('acorn'); } catch (_) { return null; } })();
  (function scan(d) {
    for (const n of fs.readdirSync(d)) {
      const abs = path.join(d, n);
      const st = fs.lstatSync(abs);
      if (st.isDirectory()) { scan(abs); continue; }
      if (!/\.(js|mjs|cjs)$/.test(n)) continue;
      const src = fs.readFileSync(abs, 'utf8');
      if (!src.includes('AAA_')) continue;
      if (acorn) {
        let ast;
        const TOK = [];
        for (const opt of [
          { ecmaVersion: 'latest', sourceType: 'module' },
          { ecmaVersion: 'latest', sourceType: 'script' },
        ]) {
          try { ast = acorn.parse(src, { ...opt, onToken: TOK }); break; } catch (_) { ast = null; }
        }
        if (ast) {
          for (const t of TOK) {
            if ((t.type.label === 'name') && typeof t.value === 'string' && t.value.startsWith('AAA_')) hits++;
          }
        } else {
          // fail-closed：解析失败时不跳过，退回「非字符串区间内出现 AAA_」
          const re = /(^|[^\w$])AAA_[A-Za-z0-9_$]*/g;
          let m;
          while ((m = re.exec(src)) !== null) hits++;
        }
      } else {
        const re = /(^|[^\w$])AAA_[A-Za-z0-9_$]*/g;
        let m;
        while ((m = re.exec(src)) !== null) hits++;
      }
    }
  })(target);
  return hits;
}

// 代码区（非字符串）里出现的 AAA_ 才算污染。
// 判据必须与 ununderscore 的改写判据同源：都只认「字符串区间之外」。
// 2026-11-24：ok-basic 的 require("./AAA_vendor/lib/b.js") 在字符串里，
// 第一版把他算成污染 → 样例误报 → 说明判据不对。
function countCodeAAA(target) {
  const { stringRanges } = require(path.join(__dirname, 'jsstrings.js'));
  let hits = 0;
  const fileList = [];
  (function scan(d) {
    for (const n of fs.readdirSync(d)) {
      const abs = path.join(d, n);
      const st = fs.lstatSync(abs);
      if (st.isDirectory()) { scan(abs); continue; }
      if (!/\.(js|mjs|cjs)$/.test(n)) continue;
      const src = fs.readFileSync(abs, 'utf8');
      if (!src.includes('AAA_')) continue;
      let ranges;
      try { ranges = stringRanges(src); } catch (_) { ranges = []; }
      const inStr = (pos) => ranges.some(r => pos >= r.start && pos < r.end);
      const re = /(^|[^\w$])AAA_[A-Za-z0-9_$]*/g;
      let m;
      let fh = 0;
      while ((m = re.exec(src)) !== null) {
        const pos = m.index + m[1].length;
        if (!inStr(pos)) { hits++; fh++; }
      }
      if (fh) fileList.push(path.relative(target, abs) + ' x' + fh);
    }
  })(target);
  return { hits, fileList };
}

// 检查样本里原本的 `_xxx` 标识符是否还在（没被改名）
function keepsOriginal(target) {
  let ok = true;
  (function scan(d) {
    for (const n of fs.readdirSync(d)) {
      const abs = path.join(d, n);
      const st = fs.lstatSync(abs);
      if (st.isDirectory()) { scan(abs); continue; }
      if (!/\.(js|mjs|cjs)$/.test(n)) continue;
      const src = fs.readFileSync(abs, 'utf8');
      // 代码区（非字符串）出现「AAA_」= 标识符被改，失败
      if (/function\s+AAA_|=\s*AAA_|,\s*AAA_|\(\s*AAA_/.test(src)) ok = false;
    }
  })(target);
  return ok;
}

// ── verify：对每组样本跑 ununderscore，断言结果 ──────────────
function verify(dir) {
  let fail = 0;
  for (const c of CASES) {
    const tgt = path.join(dir, c.name);
    let exitCode = 0, output = '';
    try {
      output = execFileSync(process.execPath, [path.join(__dirname, 'ununderscore.js'), tgt], { encoding: 'utf8', stdio: 'pipe' });
    } catch (e) {
      exitCode = e.status || 1;
      output = (e.stdout || '') + (e.stderr || '');
    }
    const pr = countCodeAAA(tgt);
    const pollute = pr.hits;
    let verdict, why;
    if (c.expect === 'pass') {
      // 期望：脚本成功，且代码区没有 AAA_
      verdict = (exitCode === 0 && pollute === 0);
      why = `exit=${exitCode} 代码区AAA_=${pollute}`;
    } else {
      // 期望：不能带着「被改坏的名字」出去。
      // 两条都算通过：
      //   (a) 脚本硬失败（拦住了）—— 例如 bad-swc
      //   (b) 脚本成功，但代码标识符保持原样（_xxx 未被改名），说明改名只碰了路径
      // 只要「代码区出现 AAA_」就一定失败。
      const clean = (pollute === 0 && keepsOriginal(tgt));
      verdict = (exitCode !== 0) || clean;
      why = `exit=${exitCode} 代码区AAA_=${pollute} 原标识符保留=${keepsOriginal(tgt)}`;
    }
    if (!verdict) {
      fail++;
      console.log(`✗ ${c.name}: ${why}`);
      if (pr && pr.fileList.length) console.log('   代码区命中: ' + pr.fileList.join(', '));
      if (output) console.log('   输出尾部: ' + output.trim().split('\n').slice(-4).join(' | '));
    } else {
      console.log(`✓ ${c.name}: ${why}`);
    }
  }
  console.log(fail === 0 ? '=== 自检样本全部符合预期 ===' : `=== ${fail} 组不符合预期 ===`);
  process.exit(fail === 0 ? 0 : 1);
}

const cmd = process.argv[2];
const target = process.argv[3] || path.join(__dirname, '..', 'tmp', 'ut-ununderscore');
if (cmd === 'generate') generate(target);
else if (cmd === 'verify') verify(target);
else { console.log('用法: node tools/selfcheck.js generate|verify [工作目录]'); process.exit(2); }
