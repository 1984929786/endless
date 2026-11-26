const fs = require('fs');
const path = require('path');

const root = 'openclaw-runtime/node_modules/openclaw';

function isKnownPkg(name) {
  return ['ajv', 'zod', 'playwright-core', 'hast', 'mdast-util-to-hast',
    'stringify-entities', 'property-information', 'import-in-the-middle',
    '@buape/carbon', 'canvas', 'semver', 'debug', 'ms', 'buffer',
    'events', 'stream', 'util', 'string_decoder', 'url'].includes(name);
}

function scanDir(dir, deps) {
  for (const e of fs.readdirSync(dir, {withFileTypes:true})) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) scanDir(f, deps);
    else if (e.name.endsWith('.js')) {
      try {
        const src = fs.readFileSync(f, 'utf8');
        const re = /(?:require\(['"`]|from\s+['"`])[^'"`]+(?=['"`])/g;
        let m;
        while ((m = re.exec(src)) !== null) {
          let mod = m[0].replace(/.*['"`]/, '').replace(/['"`].*/, '');
          if (mod.startsWith('node:') || mod.startsWith('.') || mod.startsWith('@openclaw/')) continue;
          if (mod.startsWith('../../') || mod.startsWith('../')) continue;
          if (mod.startsWith('@') || isKnownPkg(mod)) deps.add(mod);
        }
      } catch(_) {}
    }
  }
}

const deps = new Set();
scanDir(path.join(root, 'dist'), deps);
console.log('Found', deps.size, 'external deps:');
for (const d of [...deps].sort()) console.log(' ', d);
