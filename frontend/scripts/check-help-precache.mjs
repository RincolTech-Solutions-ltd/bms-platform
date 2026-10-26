// After `npm run build` (issue #598, ADR-095): the built service worker precaches every picture of the help articles, so How do
// I...? works offline. A change to the PWA's includeAssets or globPatterns that drops them fails here, not on a pilot's phone.
import { readFileSync, readdirSync } from 'node:fs';

const sw = readFileSync(new URL('../dist/sw.js', import.meta.url), 'utf8');
const pictures = readdirSync(new URL('../public/help/', import.meta.url)).filter((n) => n.endsWith('.webp'));
const missing = pictures.filter((name) => !sw.includes(`"help/${name}"`));
if (pictures.length === 0 || missing.length > 0) {
  console.error(`The service worker does not precache ${missing.length || 'any'} help picture(s): ${missing.join(', ')}`);
  process.exit(1);
}
console.log(`The service worker precaches all ${pictures.length} help pictures.`);
