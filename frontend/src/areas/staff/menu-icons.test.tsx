import type { ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../api/client';
import { icons } from '../../components/icons';
import { FEATURES } from '../member/features';
import { AREAS } from '../platform/operator-state';
import { CATALOGUE_LINKS, SCREENS } from './retail/permissions';
import { TILE_ICONS } from './retail/catalogue';
import { SHORTCUTS } from './retail/nav';
import { STAFF_SECTIONS, staffAreas } from './route';

// Every item of a menu has its own icon (issue #483): two items of one menu never draw the same picture, compared by the drawing
// itself, so two names for one drawing are caught too. The same item keeps its icon wherever it shows (a retail shortcut and its
// tile, the Shop and Lending headings and their cards on Home, checked in home-cards.dom.test.tsx), which is why the tiles and
// shortcuts read their icon by the screen's name. Business set-up is one page of fields that draws no icons today;
// setup.dom.test.tsx fails if it ever draws one picture twice.

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

// A session that holds every permission, apart from those `without` names, in a business with both modules and report runs on.
// The gates ask with includes or some; the full list of labels below fails if a gate reads them another way and drops an item.
function everything(without: string[] = []): Me {
  const permissions = Object.assign([] as string[], { includes: (p: string) => !without.includes(p), some: () => true });
  return { permissions, modules: ['retail', 'lending'], module_capabilities: [], branches: [], reports_enabled: true } as unknown as Me;
}

// The side menu: its items, the retail shortcuts under Retail, and the two section headings.
function sideMenu(me: Me) {
  const shortcuts = Object.entries(SHORTCUTS).map(([screen, label]) => ({ label: `Retail ${label}`, icon: icons[screen as keyof typeof icons] }));
  return [...staffAreas(me, { sells: true }), ...shortcuts, ...STAFF_SECTIONS.map((s) => ({ label: `${s.label} heading`, icon: s.icon }))];
}

const AREA_LABELS = ['Home', 'Approvals', 'Loans', 'Members', 'Savings', 'Investments', 'Insights', 'AI assistant', 'Reports', 'Retail', 'SMS', 'Receive payments', 'Users', 'Notifications', 'Feedback', 'Business set-up'];

describe('menu icons (issue #483)', () => {
  it('gives every item of the staff side menu its own icon', () => {
    expect(staffAreas(everything(), { sells: true }).map((l) => l.label)).toEqual(AREA_LABELS);
    expect(clashes(sideMenu(everything()))).toEqual([]);
    // Collateral takes the place of Members for a session that may not read members.
    const collateral = everything(['lending.members.read']);
    expect(staffAreas(collateral, { sells: true }).map((l) => l.label)).toEqual(AREA_LABELS.map((l) => (l === 'Members' ? 'Collateral' : l)));
    expect(clashes(sideMenu(collateral))).toEqual([]);
  });

  it('gives every retail tile its own icon', () => {
    expect(clashes(SCREENS.map((s) => ({ label: s.label, icon: icons[s.screen as keyof typeof icons] })))).toEqual([]);
  });

  it('gives every catalogue tile its own icon, the import tile among them', () => {
    const tiles = [...CATALOGUE_LINKS.filter((l) => l.screen !== 'importer'), { screen: 'importer' as const, label: 'Import items' }];
    expect(clashes(tiles.map((l) => ({ label: l.label, icon: icons[TILE_ICONS[l.screen] ?? 'catalogue'] })))).toEqual([]);
  });

  it('gives every area of the operator portal its own icon', () => {
    expect(clashes(AREAS)).toEqual([]);
  });

  it('gives every card of the member portal its own icon', () => {
    expect(clashes(FEATURES.map((f) => ({ label: f.title, icon: icons[f.icon] })))).toEqual([]);
  });

  it('catches two items that share a drawing', () => {
    expect(clashes([{ label: 'Banked', icon: icons.banking }, { label: 'Receive payments', icon: icons.banking }])).toEqual(['Receive payments = Banked']);
  });
});
