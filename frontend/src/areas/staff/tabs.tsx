import { useQuery } from '@tanstack/react-query';
import { Link, useRouterState } from '@tanstack/react-router';
import { type ReactNode, useEffect, useState } from 'react';
import type { Me } from '../../api/client';
import { retailAnalytics } from '../../api/retail-analytics';
import { ALL_BRANCHES } from '../../auth/branch';
import { icons } from '../../components/icons';
import { useIsPhone } from '../../components/side-menu';
import { useOptionalStaff } from './context';
import { homeParts } from './home-cards';
import { showSavings } from './lending/savings-permissions';
import { staffAreas } from './menu';
import { showReceivePayments } from './payments/state';
import { useSells } from './retail/nav';
import { SCREENS, canUse, showRetail, type RetailScreen } from './retail/permissions';

// The bottom tabs on a phone (issue #539, decisions D29 and D31): the five daily jobs, Sell, My stock, Buy, Money and More, each an
// icon above its label. Below 720px they replace the side menu's drawer; from 720px the sidebar stays and there are no tabs. A tab
// whose module or capability is off, or whose pages the session may not use, is not drawn; More is always there and lists the
// staff areas the session may open (menu.tsx). The bar is fixed to the bottom, takes the iPhone's home indicator inset, and sets
// the class has-tab-bar on the root while it shows, so a sticky action bar (the sale's total, a save bar) sits on top of it and
// never under it (navigation.css). While a text field has focus the bar steps aside, as the phone's keyboard is up.

export type TabId = 'sell' | 'stock' | 'buy' | 'money' | 'more';

export interface Tab {
  id: TabId;
  to: string;
  label: string;
  icon: ReactNode;
  /** The pages on which this tab is the current one, each with the pages under it. */
  paths: string[];
}

const pathOf = (screen: RetailScreen) => SCREENS.find((s) => s.screen === screen)?.path ?? '';

/** The money pages under the Money tab: the cash book, then who owes and what is owed, then lending savings and payments in. */
const MONEY_SCREENS: RetailScreen[] = [
  'cashSummary', 'banking', 'savings', 'expenses', 'withdrawals', 'advances', 'creditSales', 'creditControl', 'customerAccounts', 'customerAgeing', 'supplierPayables',
];

export interface MoneyLink {
  to: string;
  label: string;
  hint: string;
}

/** The money pages the session may use, in the order the Money page lists them. Empty means no Money tab. */
export function moneyLinks(me: Me): MoneyLink[] {
  const retail = showRetail(me) ? SCREENS.filter((s) => MONEY_SCREENS.includes(s.screen) && canUse(me, s.screen)) : [];
  const links: MoneyLink[] = retail
    .sort((a, b) => MONEY_SCREENS.indexOf(a.screen) - MONEY_SCREENS.indexOf(b.screen))
    .map((s) => ({ to: s.path, label: s.screen === 'creditSales' ? 'Customers who owe' : s.label, hint: s.hint }));
  if (showSavings(me)) links.push({ to: '/staff/lending/savings', label: 'Member savings', hint: 'Deposits and withdrawals of members' });
  if (showReceivePayments(me)) links.push({ to: '/staff/payments', label: 'Receive payments', hint: 'Mobile money and card payments into the business' });
  return links;
}

/** The tabs the session gets, in their fixed order. `sells` is useSells: false while every branch it may sell at is a store. */
export function staffTabs(me: Me, { sells }: { sells: boolean }): Tab[] {
  const money = moneyLinks(me);
  const tabs: (Tab | false)[] = [
    sells && { id: 'sell', to: pathOf('sale'), label: 'Sell', icon: icons.sale, paths: [pathOf('sale')] },
    showRetail(me) && canUse(me, 'stock') && { id: 'stock', to: pathOf('stock'), label: 'My stock', icon: icons.stock, paths: [pathOf('stock'), pathOf('stocktake')] },
    showRetail(me) && canUse(me, 'restock') && {
      id: 'buy',
      to: pathOf('restock'),
      label: 'Buy',
      icon: icons.restock,
      paths: [pathOf('restock'), '/staff/retail/restocks', pathOf('purchaseOrders'), pathOf('goodsReceived')],
    },
    money.length > 0 && { id: 'money', to: '/staff/money', label: 'Money', icon: icons.coins, paths: ['/staff/money', ...money.map((m) => m.to)] },
    { id: 'more', to: '/staff/more', label: 'More', icon: icons.menu, paths: ['/staff/more'] },
  ];
  return tabs.filter((t): t is Tab => t !== false);
}

const under = (pathname: string, path: string) => pathname === path || pathname.startsWith(`${path}/`);

/** The tab current on `pathname`, if any: Home and the pages only More lists have none. */
export function currentTab(tabs: Tab[], pathname: string): TabId | undefined {
  return tabs.find((t) => t.paths.some((p) => under(pathname, p)))?.id;
}

const TEXT_ENTRY = 'input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]):not([type=range]):not([type=file]), textarea, select, [contenteditable="true"]';
const typingIn = (el: EventTarget | null) => el instanceof Element && el.matches(TEXT_ENTRY);

/** True while a text field has focus: the phone's keyboard is up, so the tabs step aside and the action bar sits on the keyboard. */
function useTyping(): boolean {
  const [typing, setTyping] = useState(() => typeof document !== 'undefined' && typingIn(document.activeElement));
  useEffect(() => {
    const onIn = (event: FocusEvent) => setTyping(typingIn(event.target));
    const onOut = (event: FocusEvent) => setTyping(typingIn(event.relatedTarget));
    document.addEventListener('focusin', onIn);
    document.addEventListener('focusout', onOut);
    return () => {
      document.removeEventListener('focusin', onIn);
      document.removeEventListener('focusout', onOut);
    };
  }, []);
  return typing;
}

/** How many items are low on stock in the branch chosen: the Home summary's own read, so the two share one request. */
function useLowStock(me: Me): number {
  const branch = useOptionalStaff()?.branch ?? null;
  const parts = homeParts(me, branch);
  const branchId = branch && branch !== ALL_BRANCHES ? branch : undefined;
  const home = useQuery({
    queryKey: ['retail', 'home', branchId ?? 'all'],
    queryFn: ({ signal }) => retailAnalytics.home(branchId, signal),
    enabled: parts.lowStock && branch !== null,
  });
  return parts.lowStock ? (home.data?.low_stock ?? 0) : 0;
}

/**
 * The tab bar itself, on any screen size (the tour test draws it beside the sidebar). Each tab is a link with aria-current on the
 * current one; My stock carries a badge with the count of items low on stock, read out with its label. The More tab also answers
 * for the tour's menu anchors (data-tour-also), since on a phone the links they name live under More.
 */
export function TabBar({ me }: { me: Me }) {
  const sells = useSells(me);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const low = useLowStock(me);
  const tabs = staffTabs(me, { sells });
  const current = currentTab(tabs, pathname);
  const underMore = staffAreas(me, { sells }).flatMap((l) => (l.tour ? [l.tour] : []));
  return (
    <nav className="tab-bar" aria-label="Main" data-tour="tab-bar" data-tour-also="retail-nav">
      <ul>
        {tabs.map((t) => {
          const badge = t.id === 'stock' && low > 0 ? low : 0;
          return (
            <li key={t.id}>
              <Link
                to={t.to as never}
                className="tab"
                aria-current={t.id === current ? 'page' : undefined}
                data-tour={`tab-${t.id}`}
                data-tour-also={t.id === 'more' ? ['nav-areas', ...underMore].join(' ') : undefined}
              >
                <span className="tab-icon">
                  {t.icon}
                  {badge > 0 && (
                    <span className="tab-badge" aria-hidden="true">
                      {badge > 99 ? '99+' : badge}
                    </span>
                  )}
                </span>
                <span className="tab-label">{t.label}</span>
                {badge > 0 && <span className="visually-hidden">, {badge} {badge === 1 ? 'item' : 'items'} low on stock</span>}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/** The tabs on a phone only, and not while a text field has focus; the root carries has-tab-bar while they show. */
export function StaffTabBar({ me }: { me: Me }) {
  const phone = useIsPhone();
  const typing = useTyping();
  const shown = phone && !typing;
  useEffect(() => {
    if (!shown) return;
    const root = document.documentElement;
    root.classList.add('has-tab-bar');
    return () => root.classList.remove('has-tab-bar');
  }, [shown]);
  return shown ? <TabBar me={me} /> : null;
}
