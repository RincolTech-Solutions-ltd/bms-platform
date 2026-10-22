// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { type ReactNode, useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { TourProvider, useTour } from '../../tour/host';
import type { Tour } from '../../tour/model';
import { StaffContext } from './context';
import { StaffBar } from './route';

// The phone side menu of the staff bar (issue #455): below 720px a menu button opens a drawer from the left with the areas the
// session may open (the same checks as the tabs), the current one marked, the retail shortcuts as a group under Retail, and the
// name and Sign out at the bottom. It closes on Escape, the backdrop, a left swipe or a chosen link and gives focus back to the
// button. From 720px the tabs are unchanged. A tour step that points at a menu item opens the drawer. Every name is invented.

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };

const officer = {
  user_id: '00000000-0000-4000-8000-0000000000a1',
  full_name: 'Test Officer 01',
  kind: 'staff',
  permissions: ['lending.loans.read', 'lending.members.read', 'lending.insights.read', 'core.approvals.read'],
  modules: ['lending'],
  branches: [],
  tours: TOURS_OFF,
} as unknown as Me;

const admin = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;

function widthIs(phone: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({ matches: phone && query.includes('max-width: 719px'), media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
  );
}

function show(me: Me, { at = '/staff', children = null as ReactNode } = {}) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  const signOut = vi.fn();
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>
          <TourProvider me={me} saveProgress={false}>
            <StaffBar me={me} branch={null} onBranch={() => undefined} onSignOut={signOut} />
            {children}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return { router, signOut };
}

const menuButton = () => screen.getByRole('button', { name: 'Menu' });
const drawer = () => screen.queryByRole('dialog', { name: 'Menu' });

function openMenu() {
  fireEvent.click(menuButton());
  const found = drawer();
  expect(found).not.toBeNull();
  return found as HTMLElement;
}

const labels = (dialog: HTMLElement) =>
  within(within(dialog).getByRole('navigation', { name: 'Staff areas' }))
    .getAllByRole('link')
    .map((a) => a.textContent);

beforeEach(() => widthIs(true));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.body.className = '';
});

describe('the staff side menu on a phone (issue #455)', () => {
  it('shows a menu button instead of the tabs, and the button opens the drawer as a modal dialog', () => {
    show(officer);
    expect(screen.queryByRole('navigation', { name: 'Staff areas' })).toBeNull();
    expect(drawer()).toBeNull();
    expect(menuButton().getAttribute('aria-expanded')).toBe('false');
    const dialog = openMenu();
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(menuButton().getAttribute('aria-expanded')).toBe('true');
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(document.body.classList.contains('side-menu-open')).toBe(true);
  });

  it('lists only the areas the session may open, with Notifications and Feedback, then the name and Sign out', () => {
    const { signOut } = show(officer);
    const dialog = openMenu();
    expect(labels(dialog)).toEqual(['Home', 'Approvals', 'Loans', 'Members', 'Insights', 'Notifications', 'Feedback']);
    expect(within(dialog).getByText('Test Officer 01')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out' }));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('marks the current area with aria-current', () => {
    show(officer, { at: '/staff/notifications' });
    const dialog = openMenu();
    expect(within(dialog).getByRole('link', { name: 'Notifications' }).getAttribute('aria-current')).toBe('page');
    expect(within(dialog).getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
  });

  it('closes on Escape and gives focus back to the menu button', async () => {
    show(officer);
    openMenu();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(drawer()).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
    expect(document.body.classList.contains('side-menu-open')).toBe(false);
  });

  it('closes on the backdrop and gives focus back to the menu button', async () => {
    show(officer);
    openMenu();
    fireEvent.click(document.querySelector('.side-menu-backdrop') as Element);
    expect(drawer()).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
  });

  it('closes on a left swipe but not on a scroll', () => {
    show(officer);
    const dialog = openMenu();
    fireEvent.touchStart(dialog, { touches: [{ clientX: 200, clientY: 300 }] });
    fireEvent.touchEnd(dialog, { changedTouches: [{ clientX: 190, clientY: 120 }] });
    expect(drawer()).not.toBeNull();
    fireEvent.touchStart(dialog, { touches: [{ clientX: 200, clientY: 300 }] });
    fireEvent.touchEnd(dialog, { changedTouches: [{ clientX: 80, clientY: 310 }] });
    expect(drawer()).toBeNull();
  });

  it('keeps Tab inside the drawer', () => {
    show(officer);
    const dialog = openMenu();
    const signOut = within(dialog).getByRole('button', { name: 'Sign out' });
    signOut.focus();
    fireEvent.keyDown(signOut, { key: 'Tab' });
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Close menu' }));
    fireEvent.keyDown(document.activeElement as Element, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(signOut);
  });

  it('goes to the chosen area and closes', async () => {
    const { router } = show(officer);
    const dialog = openMenu();
    fireEvent.click(within(dialog).getByRole('link', { name: 'Feedback' }));
    expect(drawer()).toBeNull();
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/feedback'));
    await waitFor(() => expect(document.activeElement).toBe(menuButton()));
  });

  it('shows the retail shortcuts as a group under Retail only when the session has retail', () => {
    show(admin);
    const dialog = openMenu();
    expect(labels(dialog)).toContain('Retail');
    const group = within(dialog).getByRole('list', { name: 'Retail' });
    expect(group.getAttribute('data-tour')).toBe('retail-nav');
    expect(within(group).getAllByRole('link').length).toBeGreaterThan(0);
    cleanup();
    show(officer);
    const lending = openMenu();
    expect(within(lending).queryByRole('list', { name: 'Retail' })).toBeNull();
    expect(labels(lending)).not.toContain('Retail');
  });

  it('keeps the tour anchors in the page while closed, so optional tour steps are not skipped', () => {
    show(officer);
    for (const id of ['nav-areas', 'nav-approvals', 'nav-lending', 'nav-members', 'nav-insights', 'nav-notifications', 'nav-feedback']) {
      expect(document.querySelector(`[data-tour="${id}"]`), id).not.toBeNull();
    }
    cleanup();
    show(admin);
    for (const id of ['nav-areas', 'nav-retail', 'retail-nav', 'nav-notifications', 'nav-feedback']) {
      expect(document.querySelector(`[data-tour="${id}"]`), id).not.toBeNull();
    }
  });

  it('opens for a tour step that points at a menu item, and closes when the tour ends', async () => {
    const tour: Tour = {
      id: 'test-menu-tour',
      version: 1,
      title: 'Test tour',
      audience: () => true,
      autoStart: false,
      steps: [{ id: 'feedback', target: 'nav-feedback', title: 'Feedback here', body: 'Tap Feedback to write to us.' }],
    };
    function Starter() {
      const { start } = useTour();
      useEffect(() => start(tour), [start]);
      return null;
    }
    // jsdom has neither CSS.escape nor scrollIntoView, which the tour runtime uses to find and show its target.
    vi.stubGlobal('CSS', { escape: (value: string) => value });
    Element.prototype.scrollIntoView = vi.fn();
    show(officer, { children: <Starter /> });
    await screen.findByRole('heading', { name: 'Feedback here' });
    const panel = document.querySelector('.side-menu') as HTMLElement;
    expect(panel.hidden).toBe(false);
    expect(panel.classList.contains('side-menu-instant')).toBe(true);
    expect(document.activeElement?.textContent).toBe('Feedback here');
    act(() => {
      fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    });
    await waitFor(() => expect(panel.hidden).toBe(true));
  });
});

describe('the staff bar from 720px', () => {
  it('keeps the tabs and the Sign out button, with no menu button', () => {
    widthIs(false);
    show(admin);
    const tabs = screen.getByRole('navigation', { name: 'Staff areas' });
    expect(tabs.className).toBe('tabs');
    expect(tabs.getAttribute('data-tour')).toBe('nav-areas');
    expect(within(tabs).getByRole('link', { name: 'Retail' }).getAttribute('data-tour')).toBe('nav-retail');
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
  });
});
