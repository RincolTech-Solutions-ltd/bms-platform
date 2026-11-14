import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { router } from '../app/router';

// The UX audit (docs/ui/ux-audit.md, issue #336) has one row per staff route, so a route added or removed without its row fails
// here, as route-coverage.test.ts does for the page tours. A row's first cell is the route in backticks.

// Found from this file, so the test runs from the repository root or from frontend alike.
const audit = readFileSync(fileURLToPath(new URL('../../../docs/ui/ux-audit.md', import.meta.url)), 'utf8');
const section = audit.slice(audit.indexOf('## The audit'), audit.indexOf('## Screen tickets'));
const rows = [...section.matchAll(/^\| `(\/staff[^`]*)` \|/gm)].map((m) => m[1] as string);
const routes = Object.keys(router.routesByPath).filter((p) => p === '/staff' || p.startsWith('/staff/'));

describe('the UX audit covers every staff route (#336)', () => {
  it('has one row per staff route, and none for a route that is gone', () => {
    expect([...rows].sort()).toEqual([...routes].sort());
  });

  it('gives each route one row only', () => {
    expect(new Set(rows).size).toBe(rows.length);
  });

  it('states the route count it covers', () => {
    expect(audit).toContain(`all ${routes.length} staff routes`);
  });
});
