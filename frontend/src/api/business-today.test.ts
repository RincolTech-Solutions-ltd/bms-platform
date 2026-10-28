import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday, setBusinessZone } from './retail';

// Issue #667: from 21:00 to 24:00 UTC Kampala is already on the next day. Today and every default range come from
// businessToday, in the business's own zone, never from the device's calendar, so a phone or a runner in another zone asks
// for the same days as the server. That the staff shell hands the zone over is business-zone.dom.test.tsx.

afterEach(() => {
  vi.useRealTimers();
  setBusinessZone(undefined);
});

describe('businessToday', () => {
  it('is the Kampala date from 21:00 UTC, whatever the device clock says', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-28T20:59:59Z'));
    expect(businessToday()).toBe('2026-10-28');
    vi.setSystemTime(new Date('2026-10-28T21:00:00Z'));
    expect(businessToday()).toBe('2026-10-29');
    vi.setSystemTime(new Date('2026-10-28T23:59:59Z'));
    expect(businessToday()).toBe('2026-10-29');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-10-28');
    // The month and the year turn over on the same boundary.
    vi.setSystemTime(new Date('2026-12-31T22:00:00Z'));
    expect(businessToday()).toBe('2027-01-01');
  });

  it("follows the business's own zone from /me, and keeps Kampala for none or an unknown one", () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-28T22:30:00Z'));
    setBusinessZone('Africa/Lagos');
    expect(businessToday()).toBe('2026-10-28');
    setBusinessZone('Not/AZone');
    expect(businessToday()).toBe('2026-10-29');
    setBusinessZone('');
    expect(businessToday()).toBe('2026-10-29');
  });
});

// The guard: no screen reads the device's calendar day. Each of these is the mistake of #667 or can be: a date part of the
// local clock, a UTC day cut from an instant, the device's own date string, date-fns on now, and a date or time formatted
// without a timeZone. The patterns run over the whole file, so a chain split across lines is caught. A hit that really means
// it (a calendar sum on a UTC midnight, a moment shown on this phone's own clock) carries `business-day-ok: <reason>` on a
// line the hit spans; an empty reason does not count. The mock server stands in for the backend and is left out (its today
// is businessToday too). The repository has no ESLint (its lint is tsc), so this scan, like the design guard, is the rule.
const SRC = fileURLToPath(new URL('../', import.meta.url));
const ALLOW = /business-day-ok:[ \t]*\S/;
const DEVICE_DAY = [
  /\.get(?:FullYear|Month|Date|Day)\(\)/g,
  /toISOString\(\)\s*\.\s*(?:slice|substring|substr)\(\s*0\s*,\s*10\s*\)/g,
  /toISOString\(\)\s*\.\s*split\(\s*['"]T['"]\s*\)\s*\[\s*0\s*\]/g,
  /\.toDateString\(\)/g,
  /\bformat\(\s*new Date\(\s*\)/g,
];
// Formatting calls that use the device's zone unless their options name one (not timeZoneName, not undefined).
const FORMATTERS = /Intl\.DateTimeFormat\(|\.toLocale(?:Date|Time)String\(/g;
const NAMES_A_ZONE = /\btimeZone\s*:\s*(?!undefined\b)[^\s,}]/;
const COMMENTS = /\/\*[\s\S]*?\*\/|\/\/.*$/gm;

/** The end of a call's arguments: the index just after the bracket that closes the one before `open`. */
function closeOf(text: string, open: number): number {
  let depth = 1;
  let i = open;
  while (i < text.length && depth > 0) {
    if (text[i] === '(') depth += 1;
    if (text[i] === ')') depth -= 1;
    i += 1;
  }
  return i;
}

/** The lines (from 1) where `text` reads the device's calendar day without a reason given on the lines of the hit. */
export function deviceDays(text: string): number[] {
  const lines = text.split('\n');
  const lineOf = (at: number) => text.slice(0, at).split('\n').length;
  const hits = new Set<number>();
  const hit = (start: number, end: number) => {
    const first = lineOf(start);
    const last = lineOf(Math.max(start, end - 1));
    if (!lines.slice(first - 1, last).some((line) => ALLOW.test(line))) hits.add(first);
  };
  for (const re of DEVICE_DAY) for (const m of text.matchAll(re)) hit(m.index ?? 0, (m.index ?? 0) + m[0].length);
  for (const m of text.matchAll(FORMATTERS)) {
    const start = m.index ?? 0;
    const end = closeOf(text, start + m[0].length);
    if (!NAMES_A_ZONE.test(text.slice(start + m[0].length, end).replace(COMMENTS, ''))) hit(start, end);
  }
  return [...hits].sort((a, b) => a - b);
}

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !/-mock\.ts$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

describe('the business date guard', () => {
  it('finds no device calendar day in the app', () => {
    const found = sources(SRC).flatMap((path) => deviceDays(readFileSync(path, 'utf8')).map((line) => `${relative(SRC, path)}:${line}`));
    expect(found).toEqual([]);
  });

  it('catches each way of writing one', () => {
    for (const code of [
      'const day = new Date().toISOString().slice(0,10);',
      'const day = now.toISOString().slice(0, 10);',
      "const day = at.toISOString().split('T')[0];",
      'const day = d.toISOString().substring(0, 10);',
      "const day = d.toLocaleDateString('en-GB', { day: 'numeric' });",
      "const time = d.toLocaleTimeString('en-GB', { hour: '2-digit' });",
      "const day = new Intl.DateTimeFormat('en-CA').format(now);",
      "const day = new Intl.DateTimeFormat('en-GB', { timeZoneName: 'short' }).format(now);",
      "const day = new Intl.DateTimeFormat('en-CA', { timeZone: undefined }).format(now);",
      "const day = new Intl.DateTimeFormat('en-CA', { /* timeZone: 'Africa/Kampala' */ }).format(now);",
      "const day = format(new Date(), 'yyyy-MM-dd');",
      'const same = new Date().toDateString() === shown;',
      'const day = now.getDate();',
    ]) expect(deviceDays(code), code).toEqual([1]);
    expect(deviceDays("const day = new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(now);")).toEqual([]);
  });

  it('catches a chain split across lines, on the line where the match starts', () => {
    expect(deviceDays('const a = 1;\nconst day = d\n  .toISOString()\n  .slice(0, 10);\n')).toEqual([3]);
    expect(deviceDays("const a = 1;\nconst f = new Intl.DateTimeFormat('en-GB', {\n  day: 'numeric',\n});\n")).toEqual([2]);
    expect(deviceDays("const f = new Intl.DateTimeFormat('en-GB', {\n  timeZone: zone,\n});\n")).toEqual([]);
  });

  it('lets a reason on the lines of the hit excuse it, and nothing else', () => {
    const cut = 'const day = d.toISOString().slice(0, 10);';
    expect(deviceDays(`${cut} // business-day-ok: a calendar sum on a UTC midnight`)).toEqual([]);
    expect(deviceDays(`${cut} // business-day-ok:`)).toEqual([1]);
    expect(deviceDays(`${cut} // business-day-ok:   `)).toEqual([1]);
    expect(deviceDays(`// business-day-ok: the line above\n${cut}`)).toEqual([2]);
    expect(deviceDays(`${cut} // business-day-ok: this one\n${cut}`)).toEqual([2]);
    expect(deviceDays('const day = d\n  .toISOString() // business-day-ok: a calendar sum\n  .slice(0, 10);')).toEqual([]);
  });
});
