import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The client name guard (issue #680, AGENTS.md: clients are never named). No real shop or client name may appear in the code,
// the fixtures, the layout pages, the docs or the help articles: every name there is invented ("Test Shop One"). The repository
// is public, so the names are kept here only as SHA-256 hashes of the name normalised (lower case, spaces and punctuation
// removed), never in any readable form. Every text file is split into words, and each run of one to four words is normalised
// and hashed the same way, so "Some Name", "some-name" and "SOME NAME" are all caught.

const FRONTEND = fileURLToPath(new URL('../../', import.meta.url));
const ROOTS = [join(FRONTEND, 'src'), join(FRONTEND, 'layout'), join(FRONTEND, '../docs')];

// Each real shop or client name as its length and the SHA-256 of the name normalised (lower case, letters and digits only).
// The length says almost nothing, and the hash cannot be read back. Every text file is normalised the same way and each
// stretch of it of a listed length is hashed, so a name is caught however it is spelt or glued ("my-bulb shop", "XMLBulbShop",
// "info@mybulbshop.com", "bulbshops"). When a review or an issue turns up another real name, add an entry:
//   node -e "const n='<name normalised>';console.log(n.length, require('crypto').createHash('sha256').update(n).digest('hex'))"
const DENYLIST: ReadonlyArray<{ length: number; hash: string }> = [
  { length: 9, hash: '44c455b9f3c22f8b028ced92623032c4d9d820161dff13c4aed799fb8011b217' },
];

const TEXT = /\.(?:tsx?|css|html|json|md|dsl|txt|ya?ml|csv|sql)$/;
const THIS_FILE = fileURLToPath(import.meta.url);

export function normalised(text: string): string {
  return text.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

const hash = (name: string) => createHash('sha256').update(name).digest('hex');

/**
 * True when some stretch of the text, normalised, of a listed length hashes to a listed name. `known` keeps the answer for each
 * stretch already hashed, so a stretch is hashed once across all files and every file that holds a name is still reported.
 */
export function namesAClient(
  text: string,
  listed: ReadonlyArray<{ length: number; hash: string }> = DENYLIST,
  known: Map<string, boolean> = new Map(),
): boolean {
  const flat = normalised(text);
  const hashes = new Set(listed.map((e) => e.hash));
  for (const length of new Set(listed.map((e) => e.length))) {
    for (let i = 0; i + length <= flat.length; i++) {
      const piece = flat.slice(i, i + length);
      let hit = known.get(piece);
      if (hit === undefined) {
        hit = hashes.has(hash(piece));
        known.set(piece, hit);
      }
      if (hit) return true;
    }
  }
  return false;
}

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'node_modules' ? [] : files(path);
    return TEXT.test(name) && path !== THIS_FILE ? [path] : [];
  });
}

describe('no real client names (issue #680)', () => {
  it('normalises a name the way the list hashes it, and finds it in any spelling', () => {
    expect(normalised('Test  Shop-One (HQ)')).toBe('testshoponehq');
    const invented = [{ length: 11, hash: hash('testshopone') }];
    expect(namesAClient('Sold at TEST SHOP-ONE today', invented)).toBe(true);
    expect(namesAClient('Sold at test shop two', invented)).toBe(false);
    expect(namesAClient('const testShopOne = 1;', invented)).toBe(true);
    expect(namesAClient('const item2TestShopOne = 1;', invented)).toBe(true);
    // Letters glued on either side, plurals, acronyms in front and email addresses are caught too (review of #706).
    expect(namesAClient('orders@mytestshopone.com', invented)).toBe(true);
    expect(namesAClient('the TestShopOnes page', invented)).toBe(true);
    expect(namesAClient('XMLTestShopOneFeed', invented)).toBe(true);
    expect(namesAClient('testshoponeapi', invented)).toBe(true);
    expect(DENYLIST.every((e) => /^[0-9a-f]{64}$/.test(e.hash) && e.length > 0)).toBe(true);
  });

  it('reports every file that holds a name, not only the first', () => {
    const invented = [{ length: 11, hash: hash('testshopone') }];
    const known = new Map<string, boolean>();
    expect(['a Test Shop One', 'b TestShopOne', 'c nothing'].filter((t) => namesAClient(t, invented, known))).toHaveLength(2);
  });

  it('finds none in frontend/src, frontend/layout, the docs or the help articles', () => {
    const all = ROOTS.flatMap(files);
    expect(all.some((f) => f.includes(join('help', 'articles')))).toBe(true);
    // Each stretch is hashed once across all files; its answer is kept, so every file holding a name is reported.
    const known = new Map<string, boolean>();
    const found = all.filter((f) => namesAClient(readFileSync(f, 'utf8'), DENYLIST, known));
    expect(found.map((f) => relative(FRONTEND, f))).toEqual([]);
  }, 60_000);
});
