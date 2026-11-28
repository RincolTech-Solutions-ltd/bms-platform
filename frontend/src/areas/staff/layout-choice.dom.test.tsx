// @vitest-environment jsdom
import { Outlet, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter, useNavigate } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { endMainEntry, openMainAddress } from '../../auth/session';
import { LAYOUT_KEY, chooseLayout, isShopStaff, useMainEntry } from './layout-choice';
import { LayoutSwitch } from './route';

// One address per shop (issue #969, ADR-132): the main address opens the phone layout (/staff/retail) on a phone-width screen
// and for shop staff, the office layout (/staff) on a laptop for office people; the device's own choice wins; a /staff/... link
// always opens the layout it names. Fabricated people only.

const owner: Me = { ...mockMe('owner'), is_owner: true };
const admin: Me = mockMe('owner');
const seller: Me = mockMe('sales');
const role = (me: Me, ...keys: string[]): Me => ({ ...me, is_owner: false, roles: keys.map((k) => ({ role_key: k, role_name: k })) });
const lendingOnly: Me = { ...admin, modules: ['lending'] };

function phoneWidth(phone: boolean) {
  vi.stubGlobal('matchMedia', (media: string) => ({ matches: phone && media === '(max-width: 719px)', media, addEventListener: () => undefined, removeEventListener: () => undefined }));
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

/** A staff shell that only opens the layout, with a main address button standing in for the landing page and sign-in. */
function show(me: Me, start: string) {
  function Shell() {
    const opening = useMainEntry(me);
    return opening ? <p>Opening</p> : <Outlet />;
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
  const router = createRouter({ routeTree: root.addChildren([landing, staff.addChildren([office, shop, sell])]), history: createMemoryHistory({ initialEntries: [start] }) });
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

  it('follows the choice kept on the device', async () => {
    phoneWidth(true);
    localStorage.setItem(LAYOUT_KEY, 'office');
    expect(await fromMainAddress(seller)).toBe('/staff');
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

  it('opens the layout of the rule from the installed app start, /staff?start=main', async () => {
    phoneWidth(true);
    window.history.replaceState(null, '', '/staff?start=main');
    const router = show(owner, '/staff?start=main');
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
    expect(localStorage.getItem(LAYOUT_KEY)).toBe('office');
    expect(screen.getByRole('button', { name: 'Switch to phone view' })).toBeTruthy();
  });

  it('offers the phone view to the owner on a laptop', async () => {
    phoneWidth(false);
    const router = showSwitch(owner, '/staff');
    fireEvent.click(await screen.findByRole('button', { name: 'Switch to phone view' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff/retail'));
    expect(localStorage.getItem(LAYOUT_KEY)).toBe('phone');
  });

  it('is not offered to a business without the shop', async () => {
    phoneWidth(true);
    showSwitch(lendingOnly, '/staff');
    await screen.findByText('Home');
    expect(screen.queryByTestId('layout-switch')).toBeNull();
  });
});
