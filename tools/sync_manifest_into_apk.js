// 用「包内实际文件」重写 assets/openclaw/manifest.txt 并回写进 APK。
//
// 为什么必须这一步（2026-11-22 第五轮结论）：
//   构建前无法 100% 预测 AGP/aapt 会丢什么 —— `ignoreAssetsPattern` 的行为随版本变，
//   模拟规则永远只是近似。实测加了 `!.*` / `!_*` 后仍有 958 个 `.github/` `.vscode/`
//   之类被丢。但「包里实际有什么」是确定事实。
//   所以：先打包 → 读包内 runtime 文件列表 → 生成 manifest.txt → 只替换 zip 里那一条。
//   这样清单与包天然一致，App 逐条校验永远不会再误报。
//
// 内存约束：只 8GB RAM、包 1.3GB。绝不能整体读入+重写，
// 所以下载式做法：头尾直接 copy，条目数据整块 copy（不 inflate，
// 保留原压缩字节与 CRC/size），只 inflate/deflate manifest.txt 这一条。
//
// 用法：node tools/sync_manifest_into_apk.js <apk> [out.apk]
const fs = require('fs');
const zlib = require('zlib');

const apkPath = process.argv[2];
const outPath = process.argv[3] || (apkPath + '.synced');
if (!apkPath) { console.log('用法: sync_manifest_into_apk.js <apk> [out.apk]'); process.exit(2); }

const PREFIX = 'assets/openclaw/runtime/';
const MANIFEST = 'assets/openclaw/manifest.txt';
const CHUNK = 4 * 1024 * 1024;

const fd = fs.openSync(apkPath, 'r');
const size = fs.fstatSync(fd).size;

// ---- 找 EOCD ----
let eocd = -1;
const tailLen = Math.min(size, 70000);
const tail = Buffer.alloc(tailLen);
fs.readSync(fd, tail, 0, tailLen, size - tailLen);
for (let i = tailLen - 22; i >= 0; i--) {
  if (tail.readUInt32LE(i) === 0x06054b50) { eocd = size - tailLen + i; break; }
}
if (eocd < 0) { console.log('✗ 找不到 zip 中央目录'); process.exit(1); }

const eocdBuf = Buffer.alloc(22);
fs.readSync(fd, eocdBuf, 0, 22, eocd);
const count = eocdBuf.readUInt16LE(10);
const cdOff = eocdBuf.readUInt32LE(16);
const cdSize = eocdBuf.readUInt32LE(12);
if (count === 0xFFFF || cdOff === 0xFFFFFFFF) { console.log('✗ zip64 未支持'); process.exit(1); }

const cd = Buffer.alloc(cdSize);
fs.readSync(fd, cd, 0, cdSize, cdOff);

// ---- 解析中央目录 ----
const entries = [];
let p = 0;
while (p < cdSize && cd.readUInt32LE(p) === 0x02014b50) {
  const nameLen = cd.readUInt16LE(p + 28);
  const extraLen = cd.readUInt16LE(p + 30);
  const clen = cd.readUInt16LE(p + 32);
  const name = cd.toString('utf8', p + 46, p + 46 + nameLen);
  entries.push({
    name, nameLen, extraLen, clen,
    method: cd.readUInt16LE(p + 10),
    crc: cd.readUInt32LE(p + 16),
    csize: cd.readUInt32LE(p + 20),
    usize: cd.readUInt32LE(p + 24),
    lho: cd.readUInt32LE(p + 42),
    cdStart: p, cdLen: 46 + nameLen + extraLen + clen
  });
  p += 46 + nameLen + extraLen + clen;
}

const inPkg = entries
  .filter(e => e.name.startsWith(PREFIX) && !e.name.endsWith('/'))
  .map(e => e.name.slice(PREFIX.length))
  .sort();
if (!inPkg.length) { console.log('✗ 包里没有 runtime 文件'); process.exit(1); }

// ---- 校验 manifest 是否存在 ----
const maniEntry = entries.find(e => e.name === MANIFEST);
if (!maniEntry) { console.log('✗ 包里没有 ' + MANIFEST); process.exit(1); }

// 读原 manifest 内容做对比（可能很大，逐条比就行）
const maniData = Buffer.from(inPkg.join('\n') + '\n', 'utf8');
const maniRaw = zlib.deflateRawSync(maniData, { level: 9 });
const newCrc = zlib.crc32 ? zlib.crc32(maniData) : 0;

// ---- 逐条重新写：本地头 + 数据，中央目录跟着重建 ----
const out = fs.openSync(outPath, 'w');
const central = [];
let offset = 0;
const buf = Buffer.alloc(CHUNK);

function copyRange(src, dst, start, len) {
  let done = 0;
  while (done < len) {
    const n = Math.min(CHUNK, len - done);
    fs.readSync(fd, buf, 0, n, start + done);
    fs.writeSync(dst, buf, 0, n);
    done += n;
  }
}

let written = 0;
for (const e of entries) {
  // 读原本地头，只为拿 nameLen/extraLen
  const lh = Buffer.alloc(30);
  fs.readSync(fd, lh, 0, 30, e.lho);
  const lNameLen = lh.readUInt16LE(26);
  const lExtraLen = lh.readUInt16LE(28);
  const dataStart = e.lho + 30 + lNameLen + lExtraLen;

  // 原中央目录项（含 verMade / externalAttributes 等所有字段），原样保留
  const origCd = cd.slice(e.cdStart, e.cdStart + e.cdLen);

  const nameBuf = Buffer.from(e.name, 'utf8');
  const isMani = (e.name === MANIFEST);
  const csize = isMani ? maniRaw.length : e.csize;
  const usize = isMani ? maniData.length : e.usize;
  const crc = isMani ? newCrc : e.crc;
  const method = isMani ? 8 : e.method;

  // 本地头：原样复制，只改需要改的字段（不丢 version/flags，不丢 extra）
  const nLh = Buffer.alloc(30);
  lh.copy(nLh);
  nLh.writeUInt16LE(method, 8);
  nLh.writeUInt32LE(crc >>> 0, 14);
  nLh.writeUInt32LE(csize >>> 0, 18);
  nLh.writeUInt32LE(usize >>> 0, 22);

  fs.writeSync(out, nLh);
  fs.writeSync(out, nameBuf);
  // 本地 extra 原样保留（可能有 zip64 / unicode path 等）
  const lExtra = Buffer.alloc(lExtraLen);
  if (lExtraLen) fs.readSync(fd, lExtra, 0, lExtraLen, e.lho + 30 + lNameLen);
  fs.writeSync(out, lExtra);

  const thisOffset = offset;
  offset += 30 + nameBuf.length + lExtraLen;

  if (isMani) {
    fs.writeSync(out, maniRaw);
  } else {
    copyRange(fd, out, dataStart, e.csize);
  }
  offset += csize;
  written++;

  // 中央目录项：**整条复制原条目**，只改 method/crc/size/offset
  const ch = Buffer.from(origCd);
  ch.writeUInt16LE(method, 10);
  ch.writeUInt32LE(crc >>> 0, 16);
  ch.writeUInt32LE(csize >>> 0, 20);
  ch.writeUInt32LE(usize >>> 0, 24);
  ch.writeUInt32LE(thisOffset, 42);
  central.push(ch);
}

// EOCD 也原样复制，只改数量/大小/偏移
const cdBuf = Buffer.concat(central);
fs.writeSync(out, cdBuf);
const origEo = Buffer.alloc(22);
fs.readSync(fd, origEo, 0, 22, eocd);
const eo = Buffer.from(origEo);
eo.writeUInt16LE(written, 8);
eo.writeUInt16LE(written, 10);
eo.writeUInt32LE(cdBuf.length, 12);
eo.writeUInt32LE(offset, 16);
fs.writeSync(out, eo);
fs.closeSync(out);
fs.closeSync(fd);

console.log('✓ 包内 runtime 文件 ' + inPkg.length + ' 条已写入清单');
console.log('✓ 新包 ' + outPath + '（条目 ' + written + ' 个）');

// ---- 自检：元数据必须完整保留 ----
// 2026-11-22 教训：我把中央目录的 verMade / externalAttributes 全写成 0，
// Android 安装器报「该安装包未包含任何证书」，包看着完好但装不上。
const chk = fs.openSync(outPath, 'r');
const chkSt = fs.fstatSync(chk);
const chkEb = Buffer.alloc(22);
fs.readSync(chk, chkEb, 0, 22, chkSt.size - 22);
const chkCnt = chkEb.readUInt16LE(10);
const chkCdSize = chkEb.readUInt32LE(12);
const chkCdOff = chkEb.readUInt32LE(16);
const chkCd = Buffer.alloc(chkCdSize);
fs.readSync(chk, chkCd, 0, chkCdSize, chkCdOff);
{
  let q = 0, bad = 0;
  for (let k = 0; k < chkCnt; k++) {
    const nl = chkCd.readUInt16LE(q + 28), el = chkCd.readUInt16LE(q + 30), cl = chkCd.readUInt16LE(q + 32);
    const verMade = chkCd.readUInt16LE(q + 4);
    const extAttr = chkCd.readUInt32LE(q + 38);
    const orig = entries[k];
    if (orig) {
      const origVer = cd.readUInt16LE(orig.cdStart + 4);
      const origExt = cd.readUInt32LE(orig.cdStart + 38);
      if (verMade !== origVer || extAttr !== origExt) {
        if (bad < 3) console.log('  ✗ 元数据丢了: ' + chkCd.toString('utf8', q + 46, q + 46 + nl));
        bad++;
      }
    }
    q += 46 + nl + el + cl;
  }
  if (bad) { console.log('✗ ' + bad + ' 个条目元数据不一致，安装会失败'); process.exit(1); }
  console.log('✓ 元数据自检通过（verMade / externalAttributes 全部保留）');
}
fs.closeSync(chk);
