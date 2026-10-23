import type { ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../api/client';
import { icons } from '../../components/icons';
import { AREAS } from '../platform/route';
import { CATALOGUE_LINKS, SCREENS } from './retail/permissions';
import { TILE_ICONS } from './retail/catalogue';
import { SHORTCUTS } from './retail/nav';
import { STAFF_SECTIONS, staffAreas } from './route';

// Every item of a menu has its own icon (issue #483): two items of one menu never draw the same picture, compared by the drawing
// itself, so two names for one drawing are caught too. The same item keeps its icon wherever it shows (a retail shortcut and its
// tile, the Shop heading and the Shop card on Home), which is why the tiles and shortcuts read their icon by the screen's name.

const drawing = (icon: ReactNode) => (icon ? renderToStaticMarkup(<>{icon}</>) : '');

/** The labels that share a drawing with an earlier item, or have none, as `label = earlier label`. */
function clashes(items: { label: string; icon: ReactNode }[]): string[] {
  const seen = new Map<string, string>();
  const found: string[] = [];
  for (const { label, icon } of items) {
    const svg = drawing(icon);
    if (!svg) found.push(`${label} has no icon`);
    else if (seen.has(svg)) found.push(`${label} = ${seen.get(svg)}`);
    else seen.set(svg, label);
  }
  return found;
}

// A session that holds every permission, apart from those `without` names, in a business with both modules.
function everything(without: string[] = []): Me {
  const permissions = Object.assign([] as string[], { includes: (p: string) => !without.includes(p), some: () => true });
  return { permissions, modules: ['retail', 'lending'], module_capabilities: [], branches: [] } as unknown as Me;
}

// The side menu: its items, the retail shortcuts under Retail, and the two section headings.
function sideMenu(me: Me) {
  const shortcuts = Object.entries(SHORTCUTS).map(([screen, label]) => ({ label: `Retail ${label}`, icon: icons[screen as keyof typeof icons] }));
  return [...staffAreas(me, { sells: true }), ...shortcuts, ...STAFF_SECTIONS.map((s) => ({ label: `${s.label} heading`, icon: s.icon }))];
}

describe('menu icons (issue #483)', () => {
  it('gives every item of the staff side menu its own icon', () => {
    const all = sideMenu(everything());
    expect(all.map((l) => l.label)).toEqual(expect.arrayContaining(['Receive payments', 'Retail Banked', 'Retail', 'Retail Sale', 'Savings', 'Retail Savings']));
    expect(clashes(all)).toEqual([]);
    // Collateral takes the place of Members for a session that may not read members.
    const collateral = sideMenu(everything(['lending.members.read']));
    expect(collateral.map((l) => l.label)).toContain('Collateral');
    expect(clashes(collateral)).toEqual([]);
  });

  it('gives every retail tile its own icon', () => {
    expect(clashes(SCREENS.map((s) => ({ label: s.label, icon: icons[s.screen as keyof typeof icons] })))).toEqual([]);
  });

  it('gives every catalogue tile its own icon', () => {
    const tiles = [...CATALOGUE_LINKS.map((l) => ({ label: l.label, icon: icons[TILE_ICONS[l.screen] ?? 'catalogue'] })), { label: 'Import items', icon: icons.importer }];
    expect(clashes(tiles)).toEqual([]);
  });

  it('gives every area of the operator portal its own icon', () => {
    expect(clashes(AREAS)).toEqual([]);
  });

  it('heads the Shop card on Home with the Shop heading of the side menu', () => {
    expect(STAFF_SECTIONS.find((s) => s.id === 'shop')?.icon).toBe(icons.shop);
    expect(STAFF_SECTIONS.find((s) => s.id === 'lending')?.icon).toBe(icons.lending);
  });

  it('catches two items that share a drawing', () => {
    expect(clashes([{ label: 'Banked', icon: icons.banking }, { label: 'Receive payments', icon: icons.banking }])).toEqual(['Receive payments = Banked']);
  });
});
