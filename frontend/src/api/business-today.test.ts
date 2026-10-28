import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday } from './retail';

// Issue #667: from 21:00 to 24:00 UTC Kampala is already on the next day. Today and every default range come from
// businessToday, never from the device's own calendar, so a phone or a runner in another zone asks for the same days.

afterEach(() => vi.useRealTimers());

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
});

// The guard: no screen reads the device's calendar day. A date part of the local clock, or a UTC day cut from now, is the
// mistake of #667; a calendar sum on a yyyy-mm-dd works in UTC (getUTCDate) and is allowed.
const SRC = fileURLToPath(new URL('../', import.meta.url));
const DEVICE_DAY = /\.get(?:FullYear|Month|Date|Day)\(\)|new Date\(\)\.toISOString\(\)\.slice\(0, 10\)/;

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

describe('the business date guard', () => {
  it('finds no device calendar day in the app', () => {
    const found = sources(SRC).flatMap((path) =>
      readFileSync(path, 'utf8').split('\n').flatMap((line, i) => (DEVICE_DAY.test(line) ? [`${relative(SRC, path)}:${i + 1}`] : [])),
    );
    expect(found).toEqual([]);
  });
});
