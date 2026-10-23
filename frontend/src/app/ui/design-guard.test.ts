import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The design guard (#106): the production CSP (style-src 'self') refuses an inline <style> element,
// and a hard-coded colour bypasses the tokens and the tenant colour. So no component renders a
// <style> element or sets inline HTML, and no component or stylesheet of the design layer writes a
// hex colour: colours live in src/app/theme.css only.

const SRC = fileURLToPath(new URL('../../', import.meta.url));

function files(dir: string, ext: RegExp): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path, ext);
    return ext.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

const components = files(SRC, /\.tsx$/);
const stylesheets = files(join(SRC, 'app/ui'), /\.css$/);
const HEX = /#[0-9a-fA-F]{3,8}\b/;

/** The source with its comments blanked: comments cite issues (#95) and name what was removed. */
function code(file: string): string {
  return readFileSync(join(SRC, file), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
}

// Values, not paint: the business set-up screen's colour input shows a sample tenant colour.
const ALLOWED_HEX: Record<string, RegExp> = {
  'areas/staff/setup.tsx': /colour : '#0d5c75'|placeholder="#0D5C75"/,
};

describe('design guard', () => {
  it('finds the components and stylesheets it guards', () => {
    expect(components.length).toBeGreaterThan(10);
    expect(stylesheets.length).toBeGreaterThan(5);
  });

  it.each(components.map((f) => relative(SRC, f)))('%s renders no <style> element and no inline HTML', (file) => {
    const source = code(file);
    expect(source).not.toMatch(/<style[\s>]/);
    expect(source).not.toMatch(/dangerouslySetInnerHTML/);
  });

  it.each(components.map((f) => relative(SRC, f)))('%s writes no hex colour', (file) => {
    const allowed = ALLOWED_HEX[file];
    code(file)
      .split('\n')
      .forEach((line, i) => {
        if (HEX.test(line) && !(allowed && allowed.test(line))) {
          throw new Error(`${file}:${i + 1} writes a hex colour; use a token from src/app/theme.css`);
        }
      });
  });

  it.each(stylesheets.map((f) => relative(SRC, f)))('%s writes no hex colour', (file) => {
    const css = readFileSync(join(SRC, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).not.toMatch(HEX);
  });

  // Motion (#508, docs/ui-rules.md rule 13): everything eases out on the one curve. Ease-in feels slow to answer, a curve whose
  // y goes past 0..1 bounces, and `transition: all` animates layout and colour the rule keeps still.
  // JUSTIFICATION-A3: two new guard rules asked for by #508; no existing test checks motion.
  it.each([...stylesheets.map((f) => relative(SRC, f)), 'app/theme.css'])('%s eases out without bounce and never transitions all', (file) => {
    const css = code(file);
    expect(css).not.toMatch(/\bease-in\b(?!-out)/);
    expect(css).not.toMatch(/\bbounce\b|\belastic\b/);
    expect(css).not.toMatch(/transition(-property)?\s*:\s*all\b/);
    for (const [, curve] of css.matchAll(/cubic-bezier\(([^)]*)\)/g)) {
      const [, y1, , y2] = (curve as string).split(',').map(Number);
      expect([y1, y2].every((y) => (y as number) >= 0 && (y as number) <= 1), `${file}: cubic-bezier(${curve}) overshoots`).toBe(true);
    }
  });

  it('the motion tokens are 120 and 200 ms on the ease-out curve, with a display size for the key amount', () => {
    const theme = code('app/theme.css');
    expect(theme).toMatch(/--motion-fast:\s*120ms/);
    expect(theme).toMatch(/--motion:\s*200ms/);
    expect(theme).toMatch(/--ease-out:\s*cubic-bezier\(0\.2,\s*0,\s*0,\s*1\)/);
    expect(theme).toMatch(/--text-display:/);
    expect(code('app/ui/base.css')).toMatch(/prefers-reduced-motion: reduce\)\s*\{\s*:root\s*\{[^}]*--motion:\s*0ms/);
  });

  // The palette tokens do not change with the theme (#429), so a stylesheet draws with the colour roles, which do. The only
  // palette colours left are white text on a strong fill, black for a darker mix, and the QR code's fixed pair.
  it.each(stylesheets.map((f) => relative(SRC, f)))('%s draws with theme roles, not palette tokens', (file) => {
    const css = readFileSync(join(SRC, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const palette = [...css.matchAll(/var\((--rt-[a-z0-9-]+)\)/g)].map((m) => m[1]);
    expect(palette.filter((name) => !['--rt-white', '--rt-black', '--rt-grey-900'].includes(name as string))).toEqual([]);
  });
  // Hillary, 23 Oct 2026: a thick coloured stripe on the left edge of alerts, toasts and cards is a tell of generated UI.
  // Status is shown by a tinted background, an icon or words, never by a side stripe.
  it('draws no coloured side or top stripe in the design layer', () => {
    for (const file of stylesheets) {
      const css = code(relative(SRC, file));
      expect(css, relative(SRC, file)).not.toMatch(/border-(left|inline-start)(-width)?\s*:\s*([2-9]|\d{2,})px/);
      expect(css, relative(SRC, file)).not.toMatch(/border-(left|top|inline-start|block-start)\s*:\s*([2-9]|\d{2,})px\s+solid\s+var\(--color-(accent|primary|danger|warning|success)/);
      expect(css, relative(SRC, file)).not.toMatch(/inset\s+([2-9]|\d{2,})px\s+0\s+0/);
      expect(css, relative(SRC, file)).not.toMatch(/inset\s+0\s+([2-9]|\d{2,})px\s+0\s+var\(--color-(accent|primary|danger|warning|success)/);
    }
  });
});
