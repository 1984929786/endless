// install_plugin_deps.js —— 补装 openclaw 各扩展的运行时依赖到包根 node_modules
// 2026-11：适配 openclaw 2026.11.10（扩展不再带 package.json）

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const root = process.argv[2];
if (!root) { console.log('用法: install_plugin_deps.js <runtime目录>'); process.exit(2); }

const rootNM = path.join(root, 'node_modules');
if (!fs.existsSync(rootNM)) fs.mkdirSync(rootNM, { recursive: true });

// ===== 外部依赖白名单（从 dist/*.js 静态扫描得到） =====
const WANTED = [
  '@slack/web-api',
  '@aws-sdk/client-bedrock','@aws-sdk/core/client','@aws-sdk/core/httpAuthSchemes',
  '@aws-sdk/middleware-host-header','@aws-sdk/middleware-logger',
  '@aws-sdk/middleware-recursion-detection','@aws-sdk/middleware-user-agent',
  '@aws-sdk/region-config-resolver','@aws-sdk/signature-v4a','@aws-sdk/util-endpoints',
  '@smithy/config-resolver','@smithy/core','@smithy/core/schema',
  '@smithy/fetch-http-handler','@smithy/middleware-content-length',
  '@smithy/middleware-endpoint','@smithy/middleware-retry','@smithy/node-http-handler',
  '@smithy/property-provider','@smithy/protocol-http','@smithy/shared-ini-file-loader',
  '@smithy/smithy-client','@smithy/util-endpoints','@smithy/util-middleware',
  '@modelcontextprotocol/sdk/client/index.js','@modelcontextprotocol/sdk/client/sse.js',
  '@modelcontextprotocol/sdk/client/stdio.js','@modelcontextprotocol/sdk/client/streamableHttp.js',
  '@modelcontextprotocol/sdk/server/mcp.js','@modelcontextprotocol/sdk/server/stdio.js',
  '@agentclientprotocol/sdk','@anthropic-ai/vertex-sdk','@buape/carbon',
  '@clack/prompts','@grammyjs/runner','@grammyjs/transformer-throttler',
  '@jimp/utils','@mariozechner/pi-ai','@mariozechner/pi-coding-agent',
  '@opentelemetry/instrumentation','@pierre/diffs','@shikijs/transformers',
  '@sinclair/typebox',
  '@snazzah/davey-android-arm-eabi','@snazzah/davey-android-arm64',
  '@snazzah/davey-darwin-arm64','@snazzah/davey-darwin-universal',
  '@snazzah/davey-darwin-x64','@snazzah/davey-freebsd-arm64',
  '@snazzah/davey-freebsd-x64','@snazzah/davey-linux-arm-gnueabihf',
  '@snazzah/davey-linux-arm-musleabihf','@snazzah/davey-linux-arm64-gnu',
  '@snazzah/davey-linux-arm64-musl','@snazzah/davey-linux-ppc64-gnu',
  '@snazzah/davey-linux-riscv64-gnu','@snazzah/davey-linux-riscv64-musl',
  '@snazzah/davey-linux-s390x-gnu','@snazzah/davey-linux-x64-gnu',
  '@snazzah/davey-linux-x64-musl','@snazzah/davey-openharmony-arm',
  '@snazzah/davey-openharmony-arm64','@snazzah/davey-openharmony-x64',
  '@snazzah/davey-wasm32-wasi','@snazzah/davey-win32-arm32-msvc',
  '@snazzah/davey-win32-arm64-msvc','@snazzah/davey-win32-ia32-msvc',
  '@snazzah/davey-win32-x64-msvc',
  '@wasm-audio-decoders/common',
  'ajv','zod','playwright-core','hast','mdast-util-to-hast',
  'stringify-entities','property-information','import-in-the-middle',
];

function has(name) {
  return fs.existsSync(path.join(rootNM, ...name.split('/'), 'package.json'));
}

const missing = WANTED.filter(n => !has(n));
if (missing.length === 0) {
  console.log('✓ 所有扩展依赖都已在包根 node_modules，无需安装');
  process.exit(0);
}
console.log('需要安装 ' + missing.length + ' 个：');
for (const n of missing) console.log('   ' + n);

// 3. 隔离安装
const tmp = path.join(rootNM, '.plugin-deps-tmp');
fs.rmSync(tmp, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });
const pkgDeps = {};
for (const n of missing) pkgDeps[n] = '*';
fs.writeFileSync(path.join(tmp, 'package.json'), JSON.stringify({
  name: 'ywzj-plugin-deps', private: true, version: '0.0.0', dependencies: pkgDeps,
}, null, 2));

const os = process.env.NPM_PLATFORM_OS || 'linux';
const cpu = process.env.NPM_PLATFORM_CPU || 'arm64';
const args = ['install', '--no-audit', '--no-fund', '--omit=dev', '--ignore-scripts',
  '--no-package-lock', '--os=' + os, '--cpu=' + cpu];
console.log('=== npm ' + args.join(' ') + ' ===');
const t0 = Date.now();
const r = spawnSync('npm', args, { cwd: tmp, encoding: 'utf8', stdio: 'pipe', shell: false });
console.log('   npm 耗时 ' + ((Date.now() - t0) / 1000).toFixed(1) + 's');
if (r.stdout) process.stdout.write(r.stdout.slice(-3000));
if (r.status !== 0) {
  console.log('✗ npm install 失败 (status=' + r.status + ')');
  if (r.stderr) console.log(r.stderr.slice(-4000));
  process.exit(1);
}

// 4. 合并到包根
function mergeDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const name of fs.readdirSync(src)) {
    const s = path.join(src, name), d = path.join(dst, name);
    try { if (fs.statSync(d).isDirectory()) { mergeDir(s, d); continue; } } catch (_) {}
    fs.renameSync(s, d);
  }
}
console.log('=== 合并到包根 ===');
const t1 = Date.now();
mergeDir(path.join(tmp, 'node_modules'), rootNM);
console.log('   合并耗时 ' + ((Date.now() - t1) / 1000).toFixed(1) + 's');
fs.rmSync(tmp, { recursive: true, force: true });

// 5. 断言
const stillMissing = missing.filter(n => !has(n));
if (stillMissing.length) {
  console.log('✗ 合并后仍缺 ' + stillMissing.length + ' 个：');
  for (const n of stillMissing) console.log('   ' + n);
  process.exit(1);
}
console.log('✓ 扩展运行时依赖全部就位（' + WANTED.length + ' 个）');
