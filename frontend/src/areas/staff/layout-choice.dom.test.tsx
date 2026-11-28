// @vitest-environment jsdom
import { Outlet, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter, useNavigate } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { endMainEntry, mainEntryPending, openMainAddress } from '../../auth/session';
import { LAYOUT_KEY, chooseLayout, isShopStaff, layoutOfPath, useMainEntry } from './layout-choice';
import { LayoutSwitch } from './route';

// One address per shop (issue #969, ADR-132): the main address opens the phone layout (/staff/retail) on a phone-width screen
// and for shop staff, the office layout (/staff) on a laptop for office people; the device's own choice wins; a /staff/... link
// always opens the layout it names. Fabricated people only.

const owner: Me = { ...mockMe('owner'), is_owner: true };
const admin: Me = mockMe('owner');
const seller: Me = mockMe('sales');
const role = (me: Me, ...keys: string[]): Me => ({ ...me, is_owner: false, roles: keys.map((k) => ({ role_key: k, role_name: k })) });
const lendingOnly: Me = { ...admin, modules: ['lending'] };
const choiceOf = (me: Me) => `${LAYOUT_KEY}.${me.user_id ?? ''}`;

function phoneWidth(phone: boolean, standalone = false) {
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: (phone && media === '(max-width: 719px)') || (standalone && media === '(display-mode: standalone)'),
    media,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  cleanup();
  endMainEntry();
  vi.unstubAllGlobals();
});

describe('the rule (ADR-132)', () => {
  it('fits the screen for office people: phone width the phone layout, a laptop the office layout', () => {
    for (const me of [owner, admin, role(seller, 'accountant')]) {
      expect(chooseLayout(me, { phone: true, saved: null })).toBe('phone');
      expect(chooseLayout(me, { phone: false, saved: null })).toBe('office');
    }
  });

  it('always gives sellers, storekeepers and purchasing the phone layout, whatever the screen', () => {
    for (const keys of [['retail_sales'], ['retail_storekeeper'], ['retail_purchasing'], ['retail_sales', 'retail_purchasing', 'retail_till']]) {
      const me = role(seller, ...keys);
      expect(isShopStaff(me)).toBe(true);
      expect(chooseLayout(me, { phone: false, saved: null })).toBe('phone');
    }
  });

  it('treats the owner, an administrator who also sells and a supervisor as office people', () => {
    expect(isShopStaff({ ...role(seller, 'retail_sales'), is_owner: true })).toBe(false);
    expect(isShopStaff(role(seller, 'retail_sales', 'tenant_admin'))).toBe(false);
    expect(isShopStaff(role(seller, 'retail_supervisor'))).toBe(false);
    expect(isShopStaff(role(seller, 'retail_till'))).toBe(false);
    expect(chooseLayout(role(seller, 'retail_sales', 'accountant'), { phone: false, saved: null })).toBe('office');
  });

  it('lets the device choice win, and opens the office layout for a business without the shop', () => {
    expect(chooseLayout(seller, { phone: true, saved: 'office' })).toBe('office');
    expect(chooseLayout(owner, { phone: false, saved: 'phone' })).toBe('phone');
    expect(chooseLayout(lendingOnly, { phone: true, saved: 'phone' })).toBe('office');
  });
});

/**
 * A staff shell that only opens the layout, with a main address button calling the openMainAddress of the landing page and
 * sign-in. The landing page and sign-in themselves are tested in landing-main.dom.test.tsx and the sign-in tests.
 */
function show(me: Me | undefined, start: string, failed = false) {
  function Shell() {
    const { opening, routed } = useMainEntry(me, failed);
    return opening ? <p>Opening</p> : <div data-routed={String(routed)}><Outlet /></div>;
  }
  function Main() {
    const navigate = useNavigate();
    return <button type="button" onClick={() => void openMainAddress(navigate)}>Main address</button>;
  }
  const root = createRootRoute({ component: Outlet });
  const landing = createRoute({ getParentRoute: () => root, path: '/', component: Main });
  const staff = createRoute({ getParentRoute: () => root, path: '/staff', component: Shell });
  const office = createRoute({ getParentRoute: () => staff, path: '/', component: () => <h1>Home</h1> });
  const shop = createRoute({ getParentRoute: () => staff, path: '/retail', component: () => <h1>Shop</h1> });
  const sell = createRoute({ getParentRoute: () => staff, path: '/retail/sale', component: () => <h1>Sell</h1> });
  const approvals = createRoute({ getParentRoute: () => staff, path: '/approvals', component: () => <h1>Approvals</h1> });
  const router = createRouter({ routeTree: root.addChildren([landing, staff.addChildren([office, shop, sell, approvals])]), history: createMemoryHistory({ initialEntries: [start] }) });
  render(<RouterProvider router={router} />);
  return router;
}

async function fromMainAddress(me: Me) {
  const router = show(me, '/');
  fireEvent.click(await screen.findByRole('button', { name: 'Main address' }));
  await waitFor(() => expect(screen.queryByText('Opening')).toBeNull());
  await screen.findByRole('heading');
  return router.state.location.pathname;
}

describe('the main address by width', () => {
  it('opens the phone layout on a phone-width screen', async () => {
    phoneWidth(true);
    expect(await fromMainAddress(owner)).toBe('/staff/retail');
    expect(screen.getByRole('heading').textContent).toBe('Shop');
  });

  it('opens the office layout on a laptop', async () => {
    phoneWidth(false);
    expect(await fromMainAddress(owner)).toBe('/staff');
    expect(screen.getByRole('heading').textContent).toBe('Home');
  });
});

describe('the main address by role', () => {
  it('opens the phone layout for a seller on a laptop', async () => {
    phoneWidth(false);
    expect(await fromMainAddress(seller)).toBe('/staff/retail');
  });

  it('opens the office layout for a lending business on a phone', async () => {
    phoneWidth(true);
    expect(await fromMainAddress(lendingOnly)).toBe('/staff');
  });

  it('follows the choice this person kept on the device', async () => {
    phoneWidth(true);
    localStorage.setItem(choiceOf(seller), 'office');
    expect(await fromMainAddress(seller)).toBe('/staff');
  });

  it("keeps one person's choice from moving another who signs in on the same device", async () => {
    phoneWidth(false);
    localStorage.setItem(choiceOf(owner), 'office');
    const other = { ...role(seller, 'retail_sales'), user_id: '00000000-0000-4000-8000-0000000000f2' };
    expect(await fromMainAddress(other)).toBe('/staff/retail');
  });

  it('marks the layout as opened by the main address, which a link never does', async () => {
    phoneWidth(true);
    await fromMainAddress(owner);
    expect(document.querySelector('[data-routed]')?.getAttribute('data-routed')).toBe('true');
    cleanup();
    show(owner, '/staff/retail');
    await screen.findByRole('heading');
    expect(document.querySelector('[data-routed]')?.getAttribute('data-routed')).toBe('false');
  });

  it('ends the main entry when the profile cannot load, so a later link is not routed', async () => {
    phoneWidth(true);
    const router = show(undefined, '/');
    fireEvent.click(await screen.findByRole('button', { name: 'Main address' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff'));
    cleanup();
    endMainEntry();
    const failedRouter = show(undefined, '/');
    fireEvent.click(await screen.findByRole('button', { name: 'Main address' }));
    expect(mainEntryPending()).toBe(true);
    cleanup();
    show(undefined, '/staff', true);
    await screen.findByRole('heading');
    expect(mainEntryPending()).toBe(false);
    expect(failedRouter.state.location.pathname).toBe('/staff');
  });

  it('ends the main entry when the shell is left', async () => {
    const router = show(undefined, '/');
    fireEvent.click(await screen.findByRole('button', { name: 'Main address' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff'));
    expect(mainEntryPending()).toBe(true);
    cleanup();
    expect(mainEntryPending()).toBe(false);
  });
});

describe('a /staff link opens the layout it names', () => {
  it('leaves /staff on a phone for a seller', async () => {
    phoneWidth(true);
    const router = show(seller, '/staff');
    expect((await screen.findByRole('heading')).textContent).toBe('Home');
    expect(router.state.location.pathname).toBe('/staff');
  });

  it('leaves /staff/retail and a page under it on a laptop for the owner', async () => {
    phoneWidth(false);
    let router = show(owner, '/staff/retail');
    expect((await screen.findByRole('heading')).textContent).toBe('Shop');
    expect(router.state.location.pathname).toBe('/staff/retail');
    cleanup();
    router = show(owner, '/staff/retail/sale');
    expect((await screen.findByRole('heading')).textContent).toBe('Sell');
    expect(router.state.location.pathname).toBe('/staff/retail/sale');
  });

  it('leaves an office page such as /staff/approvals for a seller on a phone', async () => {
    phoneWidth(true);
    const router = show(seller, '/staff/approvals');
    expect((await screen.findByRole('heading')).textContent).toBe('Approvals');
    expect(router.state.location.pathname).toBe('/staff/approvals');
  });

  it('lets an owner on a phone reach Approvals by its link', async () => {
    phoneWidth(true);
    const router = show(owner, '/staff/approvals');
    expect((await screen.findByRole('heading')).textContent).toBe('Approvals');
    expect(router.state.location.pathname).toBe('/staff/approvals');
  });

  it('leaves bare /staff in a browser tab, even as its first page', async () => {
    phoneWidth(true);
    window.history.replaceState(null, '', '/staff');
    const router = show(owner, '/staff');
    expect((await screen.findByRole('heading')).textContent).toBe('Home');
    expect(router.state.location.pathname).toBe('/staff');
  });
});

describe('the installed app starts at the main address', () => {
  it.each([
    ['a phone', true, owner, '/staff/retail'],
    ['a laptop', false, owner, '/staff'],
    ['a laptop, for a seller', false, seller, '/staff/retail'],
  ])('opens the layout of the rule from /staff?start=main on %s', async (_name, phone, me, expected) => {
    phoneWidth(phone);
    window.history.replaceState(null, '', '/staff?start=main');
    const router = show(me, '/staff?start=main');
    await screen.findByRole('heading');
    await waitFor(() => expect(router.state.location.pathname).toBe(expected));
    expect(router.state.location.searchStr).toBe('');
  });

  it('opens it from bare /staff when an app installed before the change starts on its own', async () => {
    phoneWidth(true, true);
    window.history.replaceState(null, '', '/staff');
    const router = show(owner, '/staff');
    expect((await screen.findByRole('heading')).textContent).toBe('Shop');
    expect(router.state.location.pathname).toBe('/staff/retail');
  });
});

describe('Switch to office view or phone view', () => {
  function showSwitch(me: Me, start: string) {
    const root = createRootRoute({ component: () => <ul><LayoutSwitch me={me} /><Outlet /></ul> });
    const staff = createRoute({ getParentRoute: () => root, path: '/staff', component: Outlet });
    const office = createRoute({ getParentRoute: () => staff, path: '/', component: () => <li>Home</li> });
    const shop = createRoute({ getParentRoute: () => staff, path: '/retail', component: () => <li>Shop</li> });
    const router = createRouter({ routeTree: root.addChildren([staff.addChildren([office, shop])]), history: createMemoryHistory({ initialEntries: [start] }) });
    render(<RouterProvider router={router} />);
    return router;
  }

  it('offers the office view in the phone layout, opens it and keeps the choice', async () => {
    phoneWidth(true);
    const router = showSwitch(owner, '/staff/retail');
    const button = await screen.findByRole('button', { name: 'Switch to office view' });
    await act(async () => fireEvent.click(button));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff'));
    expect(localStorage.getItem(choiceOf(owner))).toBe('office');
    expect(await screen.findByRole('button', { name: 'Switch to phone view' })).toBeTruthy();
  });

  it('offers the phone view to the owner on a laptop', async () => {
    phoneWidth(false);
    const router = showSwitch(owner, '/staff');
    fireEvent.click(await screen.findByRole('button', { name: 'Switch to phone view' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff/retail'));
    expect(localStorage.getItem(choiceOf(owner))).toBe('phone');
  });

  it('is named from the page on screen: the office view on the shop pages a laptop link opened', async () => {
    phoneWidth(false);
    showSwitch(owner, '/staff/retail');
    expect(await screen.findByRole('button', { name: 'Switch to office view' })).toBeTruthy();
    expect(layoutOfPath('/staff/retail/sale')).toBe('phone');
    expect(layoutOfPath('/staff/retailers')).toBe('office');
    expect(layoutOfPath('/staff/approvals')).toBe('office');
  });

  it('is not offered to a business without the shop', async () => {
    phoneWidth(true);
    showSwitch(lendingOnly, '/staff');
    await screen.findByText('Home');
    expect(screen.queryByTestId('layout-switch')).toBeNull();
  });
});
