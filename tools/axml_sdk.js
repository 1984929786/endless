// axml_sdk.js —— 从 APK 的二进制 AndroidManifest.xml 里读 minSdk / targetSdk / 包名
//
// 【为什么手写】本机没有 aapt/aapt2（/system/bin 不可读），但 SDK 版本必须查准，
// 不能靠「看 gradle 配置猜」。
//
// AXML 结构（实测本机包）：
//   文件头 8 字节: type=0x0003 headerSize=8 totalSize
//   字符串池块 @8: type=0x0001 headerSize=28, [8]=count, [16]=flags, [20]=stringsStart
//   之后: RESOURCE_MAP(0x0180) / START_NAMESPACE(0x0100) / START_ELEMENT(0x0102) ...
//   START_ELEMENT: headerSize=16, [20]=name(string idx), [24]=attrStart, [26]=attrSize, [28]=attrCount
//   属性项 @ elemOff+16+attrStart+a*attrSize: [4]=nameIdx [8]=rawValue [15]=dataType [16]=data
//
// 用法: node tools/axml_sdk.js <apk>

const fs = require('fs');
const zlib = require('zlib');

function readZipEntry(apk, wantName) {
  const fd = fs.openSync(apk, 'r');
  const st = fs.fstatSync(fd);
  const eb = Buffer.alloc(22);
  fs.readSync(fd, eb, 0, 22, st.size - 22);
  const cnt = eb.readUInt16LE(10);
  const cdSize = eb.readUInt32LE(12);
  const cdOff = eb.readUInt32LE(16);
  const b = Buffer.alloc(cdSize);
  fs.readSync(fd, b, 0, cdSize, cdOff);
  let p = 0;
  for (let k = 0; k < cnt; k++) {
    const method = b.readUInt16LE(p + 10);
    const csize = b.readUInt32LE(p + 20);
    const nl = b.readUInt16LE(p + 28), el = b.readUInt16LE(p + 30), cl = b.readUInt16LE(p + 32);
    const name = b.toString('utf8', p + 46, p + 46 + nl);
    const lho = b.readUInt32LE(p + 42);
    if (name === wantName) {
      const lh = Buffer.alloc(30);
      fs.readSync(fd, lh, 0, 30, lho);
      const lnl = lh.readUInt16LE(26), lel = lh.readUInt16LE(28);
      const raw = Buffer.alloc(csize);
      fs.readSync(fd, raw, 0, csize, lho + 30 + lnl + lel);
      fs.closeSync(fd);
      return method === 0 ? raw : zlib.inflateRawSync(raw);
    }
    p += 46 + nl + el + cl;
  }
  fs.closeSync(fd);
  return null;
}

function parseStringPool(buf, off) {
  const count = buf.readUInt32LE(off + 8);
  const flags = buf.readUInt32LE(off + 16);
  const stringsStart = buf.readUInt32LE(off + 20);
  const utf8 = (flags & 0x100) !== 0;
  const base = off + stringsStart;
  const out = [];
  for (let i = 0; i < count; i++) {
    const sOff = base + buf.readUInt32LE(off + 28 + i * 4);
    try {
      if (utf8) {
        let q = sOff;
        let n = buf[q++];
        if (n & 0x80) q++;
        let len = buf[q++];
        if (len & 0x80) len = ((len & 0x7f) << 8) | buf[q++];
        out.push(buf.toString('utf8', q, q + len));
      } else {
        const len = buf.readUInt16LE(sOff);
        out.push(buf.toString('utf16le', sOff + 2, sOff + 2 + len * 2));
      }
    } catch (e) { out.push(''); }
  }
  return out;
}

function parseAxml(buf) {
  if (buf.readUInt16LE(0) !== 0x0003) throw new Error('不是 AXML');
  // 第一个 chunk 就是字符串池
  const spOff = 8;
  if (buf.readUInt16LE(spOff) !== 0x0001) throw new Error('第一个 chunk 不是字符串池');
  const strings = parseStringPool(buf, spOff);

  const res = { package: null, minSdk: null, targetSdk: null, permissions: [] };
  let p = 8;
  while (p + 8 <= buf.length) {
    const type = buf.readUInt16LE(p);
    const hsize = buf.readUInt16LE(p + 2);
    const size = buf.readUInt32LE(p + 4);
    if (size < 8 || p + size > buf.length) break;
    if (type === 0x0102) { // START_ELEMENT
      const nameIdx = buf.readUInt32LE(p + 20);
      const attrStart = buf.readUInt16LE(p + 24);
      const attrSize = buf.readUInt16LE(p + 26);
      const attrCount = buf.readUInt16LE(p + 28);
      const tagName = strings[nameIdx];
      for (let a = 0; a < attrCount; a++) {
        const ao = p + hsize + attrStart + a * attrSize;
        if (ao + 20 > buf.length) break;
        const nameIdxA = buf.readUInt32LE(ao + 4);
        const dataType = buf[ao + 15];
        const data = buf.readUInt32LE(ao + 16);
        const an = strings[nameIdxA];
        const val = (dataType === 0x03) ? strings[data] : data;
        if (tagName === 'manifest' && an === 'package') res.package = val;
        if (tagName === 'uses-sdk') {
          if (an === 'minSdkVersion') res.minSdk = val;
          if (an === 'targetSdkVersion') res.targetSdk = val;
        }
        if (tagName === 'uses-permission' && an === 'name') res.permissions.push(val);
      }
    }
    p += size;
  }
  return res;
}

const apk = process.argv[2];
if (!apk) { console.log('用法: node axml_sdk.js <apk>'); process.exit(2); }
const xml = readZipEntry(apk, 'AndroidManifest.xml');
if (!xml) { console.log('✗ 找不到 AndroidManifest.xml'); process.exit(1); }
const r = parseAxml(xml);
console.log('包名      :', r.package);
console.log('minSdk    :', r.minSdk);
console.log('targetSdk :', r.targetSdk);
if (r.permissions.length) console.log('权限      :', r.permissions.join(', '));
