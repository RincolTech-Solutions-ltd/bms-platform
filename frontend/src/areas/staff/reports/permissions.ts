import type { Me } from '../../../api/client';

// What the Reports page offers a session (extras slice D1). The server decides which reports a user may run and
// whether the tenant has switched reports on; this only decides whether the menu shows the entry.

export const REPORT_PERMISSIONS = [
  'lending.reports.portfolio',
  'lending.reports.collections',
  'lending.reports.members',
  'lending.reports.compliance',
];
export const SETTINGS_MANAGE = 'core.settings.manage';

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The Reports menu entry: the tenant has lending and the user holds a report permission. */
export function showReports(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return held(me).some((p) => REPORT_PERMISSIONS.includes(p));
}

export function canSwitchReportsOn(me: Pick<Me, 'permissions'>): boolean {
  return held(me).includes(SETTINGS_MANAGE);
}
