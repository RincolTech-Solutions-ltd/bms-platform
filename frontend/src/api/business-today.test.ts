import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday, setBusinessZone } from './retail';

// Issue #667: from 21:00 to 24:00 UTC Kampala is already on the next day. Today and every default range come from
// businessToday, in the business's own zone, never from the device's calendar, so a phone or a runner in another zone asks
// for the same days as the server.

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
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-10-28'); // business-date: allow, the UTC day it differs from
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
// local clock, a UTC day cut from an instant, a date or time formatted without a timeZone, date-fns on now. A line that
// really means it (a calendar sum on a UTC midnight, a moment shown on this phone's own clock) carries
// `business-date: allow, <why>` on that line or the one above. The mock server stands in for the backend and is left out.
const SRC = fileURLToPath(new URL('../', import.meta.url));
const ALLOW = 'business-date: allow';
const DEVICE_DAY = [
  /\.get(?:FullYear|Month|Date|Day)\(\)/,
  /toISOString\(\)\s*\.\s*(?:slice|substring|substr)\(\s*0\s*,\s*10\s*\)/,
  /toISOString\(\)\s*\.\s*split\(\s*['"]T['"]\s*\)\s*\[\s*0\s*\]/,
  /\bformat\(\s*new Date\(\s*\)/,
];
// Formatting calls that use the device's zone unless their options name one.
const FORMATTERS = /Intl\.DateTimeFormat\(|\.toLocale(?:Date|Time)String\(/g;

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !/-mock\.ts$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

/** The text of a call's arguments, from just after its opening bracket to the matching close. */
function argumentsAt(text: string, open: number): string {
  let depth = 1;
  let i = open;
  while (i < text.length && depth > 0) {
    if (text[i] === '(') depth += 1;
    if (text[i] === ')') depth -= 1;
    i += 1;
  }
  return text.slice(open, i);
}

function deviceDays(path: string): string[] {
  const text = readFileSync(path, 'utf8');
  const lines = text.split('\n');
  const allowed = (line: number) => (lines[line] ?? '').includes(ALLOW) || (lines[line - 1] ?? '').includes(ALLOW);
  const where = (line: number) => `${relative(SRC, path)}:${line + 1}`;
  const found = lines.flatMap((line, i) => (DEVICE_DAY.some((re) => re.test(line)) && !allowed(i) ? [where(i)] : []));
  for (const m of text.matchAll(FORMATTERS)) {
    const at = m.index ?? 0;
    const line = text.slice(0, at).split('\n').length - 1;
    if (!argumentsAt(text, at + m[0].length).includes('timeZone') && !allowed(line)) found.push(where(line));
  }
  return found;
}

describe('the business date guard', () => {
  it('finds no device calendar day in the app', () => {
    expect(sources(SRC).flatMap(deviceDays)).toEqual([]);
  });

  it('catches the ways of writing one', () => {
    const hits = (code: string) => DEVICE_DAY.some((re) => re.test(code)) || [...code.matchAll(FORMATTERS)].some((m) => !argumentsAt(code, (m.index ?? 0) + m[0].length).includes('timeZone'));
    for (const code of [
      'new Date().toISOString().slice(0,10)',
      'now.toISOString().slice(0, 10)',
      "at.toISOString().split('T')[0]",
      'd.toISOString().substring(0, 10)',
      "d.toLocaleDateString('en-GB', { day: 'numeric' })",
      "new Intl.DateTimeFormat('en-CA').format(now)",
      "format(new Date(), 'yyyy-MM-dd')",
      'now.getDate()',
    ]) expect(hits(code), code).toBe(true);
    expect(hits("new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(now)")).toBe(false);
  });
});
