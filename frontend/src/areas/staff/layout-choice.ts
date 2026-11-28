import { useNavigate } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import type { Me } from '../../api/client';
import { endMainEntry, mainEntryPending } from '../../auth/session';
import { PHONE } from '../../components/side-menu';
import { showRetail } from './retail/permissions';

// One address per shop (issue #969, ADR-132). The business's main address opens the layout that fits the screen and the job: the
// phone layout (the shop's pages, /staff/retail) on a phone-width screen or for shop staff, the office layout (Home, /staff) on a
// laptop for the owner, administrators and accountants. A link that names a layout always opens it: only the main address, the
// end of a sign-in and the installed app's start are routed by this rule, never a /staff/... link. The person can switch by hand
// from the menu; the choice is kept on this device and wins over the rule.

export type Layout = 'phone' | 'office';

/** Where each layout starts. */
export const LAYOUT_HOME: Record<Layout, '/staff/retail' | '/staff'> = { phone: '/staff/retail', office: '/staff' };

/** The device's own choice of layout, from the menu's switch link. */
export const LAYOUT_KEY = 'bms.layout';

/** The installed app's start (the manifest's start_url): the main address inside the app's scope. */
export const APP_START = 'start=main';

/** The roles whose work is the shop: sellers, storekeepers and purchasing (ADR-055). */
const SHOP_ROLES = ['retail_sales', 'retail_storekeeper', 'retail_purchasing'];
/** Given on top of a shop role, so it does not make an office person of a seller. */
const SHOP_ADD_ONS = ['retail_till'];

/**
 * True for shop staff: not the owner, with at least one shop role and no role beyond the shop's. An administrator, an accountant,
 * a manager or a supervisor who also sells is an office person and gets the layout that fits the screen.
 */
export function isShopStaff(me: Pick<Me, 'is_owner' | 'roles'>): boolean {
  if (me.is_owner === true) return false;
  const keys = (me.roles ?? []).map((r) => r.role_key ?? '');
  return keys.some((k) => SHOP_ROLES.includes(k)) && keys.every((k) => SHOP_ROLES.includes(k) || SHOP_ADD_ONS.includes(k));
}

/**
 * The layout the main address opens. Without the shop there is no phone layout to open. Then the device's own choice, then shop
 * staff always the phone layout, then the screen: phone width (below 720px) the phone layout, else the office layout.
 */
export function chooseLayout(me: Me, { phone, saved }: { phone: boolean; saved: Layout | null }): Layout {
  if (!showRetail(me)) return 'office';
  if (saved) return saved;
  if (isShopStaff(me)) return 'phone';
  return phone ? 'phone' : 'office';
}

/** The device's own choice, or null when none is kept or storage is blocked. */
export function savedLayout(): Layout | null {
  try {
    const value = window.localStorage.getItem(LAYOUT_KEY);
    return value === 'phone' || value === 'office' ? value : null;
  } catch {
    return null;
  }
}

/** Keeps the device's choice; with storage blocked the switch still opens the layout, only for this visit. */
export function saveLayout(layout: Layout): void {
  try {
    window.localStorage.setItem(LAYOUT_KEY, layout);
  } catch {
    // Blocked storage: nothing kept, the rule decides next time.
  }
}

/** The layout the main address opens for this person on this device now. */
export function currentLayout(me: Me): Layout {
  const phone = typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(PHONE).matches;
  return chooseLayout(me, { phone, saved: savedLayout() });
}

/** True when this load is the installed app's start (`/staff?start=main`), which is the main address inside the app. */
function startedFromApp(): boolean {
  if (typeof window === 'undefined') return false;
  const { pathname, search } = window.location;
  return (pathname === '/staff' || pathname === '/staff/') && search.slice(1).split('&').includes(APP_START);
}

/**
 * For the staff shell: while the area was opened by the main address, opens the person's layout as soon as `me` is known, and
 * returns true until it has, so the shell draws its loader rather than a page it is about to leave. Otherwise false and nothing
 * moves: a /staff/... link opens the layout it names.
 */
export function useMainEntry(me: Me | undefined): boolean {
  const navigate = useNavigate();
  const [opening, setOpening] = useState(() => mainEntryPending() || startedFromApp());
  useEffect(() => {
    if (!opening || !me) return;
    let live = true;
    void navigate({ to: LAYOUT_HOME[currentLayout(me)], replace: true }).then(() => {
      endMainEntry();
      if (live) setOpening(false);
    });
    return () => {
      live = false;
    };
  }, [opening, me, navigate]);
  return opening;
}
