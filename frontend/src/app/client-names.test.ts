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

// SHA-256 of each real shop or client name, normalised. When a review or an issue turns up another real name, add its hash:
//   node -e "console.log(require('crypto').createHash('sha256').update('<name normalised>').digest('hex'))"
const DENYLIST = new Set(['44c455b9f3c22f8b028ced92623032c4d9d820161dff13c4aed799fb8011b217']);
const MOST_WORDS = 4;

const TEXT = /\.(?:tsx?|css|html|json|md|dsl|txt|ya?ml|csv|sql)$/;
const THIS_FILE = fileURLToPath(import.meta.url);

export function normalised(text: string): string {
  return text.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

const hash = (name: string) => createHash('sha256').update(name).digest('hex');

/** True when some run of one to four words in the text, normalised, hashes to a listed name. `seen` skips runs already checked. */
export function namesAClient(text: string, listed: ReadonlySet<string> = DENYLIST, seen: Set<string> = new Set()): boolean {
  // Words are also split where a capital or a digit starts inside one, so a name glued into code ("testBulbShop", "shop2Name")
  // is still a run of words (review of #706).
  const split = text.replace(/(\p{Ll})(\p{Lu})/gu, '$1 $2').replace(/(\p{L})(\p{N})|(\p{N})(\p{L})/gu, '$1$3 $2$4');
  const words = split.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? [];
  for (let i = 0; i < words.length; i++) {
    let joined = '';
    for (let n = 0; n < MOST_WORDS && i + n < words.length; n++) {
      joined += words[i + n];
      if (seen.has(joined)) continue;
      seen.add(joined);
      if (listed.has(hash(joined))) return true;
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
    const invented = new Set([hash('testshopone')]);
    expect(namesAClient('Sold at TEST SHOP-ONE today', invented)).toBe(true);
    expect(namesAClient('Sold at test shop two', invented)).toBe(false);
    expect(namesAClient('const testShopOne = 1;', invented)).toBe(true);
    expect(namesAClient('render(<TestShopOne2 />)', invented)).toBe(true);
    expect([...DENYLIST].every((h) => /^[0-9a-f]{64}$/.test(h))).toBe(true);
  });

  it('finds none in frontend/src, frontend/layout, the docs or the help articles', () => {
    const all = ROOTS.flatMap(files);
    expect(all.some((f) => f.includes(join('help', 'articles')))).toBe(true);
    // A run checked clean in one file is clean in every file, so it is hashed once.
    const seen = new Set<string>();
    const found = all.filter((f) => namesAClient(readFileSync(f, 'utf8'), DENYLIST, seen));
    expect(found.map((f) => relative(FRONTEND, f))).toEqual([]);
  }, 60_000);
});
