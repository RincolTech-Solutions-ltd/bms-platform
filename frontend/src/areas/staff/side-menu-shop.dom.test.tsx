// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { PLATFORM_BRAND, type ShellBrand } from '../../app/branding';
import { router as appRouter } from '../../app/router';
import { ALL_BRANCHES } from '../../auth/branch';
import { TourProvider } from '../../tour/host';
import { StaffContext } from './context';
import { StaffBar, StaffSideNav } from './route';
import { MorePage } from './tab-pages';

// The shop in use at the top of the side menu and, on a phone, on the More page (issues #461 and #539, ADR-068): its own logo, small
// and rounded; a shop without one shows the business logo, and with no business logo either, the menu shows the business's initials
// (issue #564) and the More page only the name. Every name below is invented.

const LOGO = '/api/v1/branches/b-two/logo?v=doc-1';
const BUSINESS = '/api/v1/branding/logo?v=doc-9';

let brand: ShellBrand = PLATFORM_BRAND;
vi.mock('../../app/branding', async (original) => ({
  ...(await original<typeof import('../../app/branding')>()),
  useShellBrand: () => brand,
}));

const me = {
  user_id: 'u-self',
  full_name: 'Test Cashier 01',
  business_name: 'Test Traders',
  kind: 'staff',
  permissions: [],
  modules: [],
  all_branches: true,
  branches: [
    { id: 'b-one', code: 'HQ', name: 'Test Shop One', is_head_office: true, stocks_goods: true, sells_goods: true, logo_url: null },
    { id: 'b-two', code: 'KLA', name: 'Test Shop Two', is_head_office: false, stocks_goods: true, sells_goods: true, logo_url: LOGO },
  ],
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;

function widthIs(px: number) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: px <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function show(branch: string | null, who: Me = me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })}>
        <StaffContext.Provider value={{ me: who, branch }}>
          <TourProvider me={who} saveProgress={false}>
            <StaffSideNav me={who} />
            <StaffBar me={who} branch={branch} onBranch={() => undefined} onSignOut={() => undefined} />
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const sidebarShop = () => document.querySelector('.side-nav .menu-shop') as HTMLElement;

beforeEach(() => {
  brand = PLATFORM_BRAND;
  window.scrollTo = vi.fn();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.body.className = '';
  window.localStorage.clear();
});

describe('the shop in use at the top of the sidebar (issues #461, #564)', () => {
  beforeEach(() => widthIs(1280));

  it('shows the shop logo, rounded, with the business name and the branch at the right', async () => {
    brand = { name: 'Test Traders', logoSrc: null, themePrimary: null, themeText: null };
    show('b-two');
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(image.className).toBe('menu-shop-logo');
    expect(within(sidebarShop()).getByText('Test Traders').className).toBe('menu-shop-name');
    expect(sidebarShop().querySelector('.menu-shop-branch-name')?.textContent).toBe('Test Shop Two (KLA)');
  });

  it('holds an empty space the size of the logo while the shop logo loads, never the business logo', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show('b-two');
    const space = sidebarShop().querySelector('.menu-shop-logo') as HTMLElement;
    expect(space.tagName).toBe('SPAN');
    expect(space.getAttribute('aria-hidden')).toBe('true');
    expect(within(sidebarShop()).queryByRole('img')).toBeNull();
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(sidebarShop().querySelectorAll('.menu-shop-logo')).toHaveLength(1);
  });

  it('falls back to the business logo for a shop without its own', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show('b-one');
    // The image's text names whose logo it is: here the business's, not the shop's.
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Traders')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(BUSINESS);
  });

  it('falls back to the business logo when the shop logo fails to load, and to the initials when that fails too', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show('b-two');
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    fireEvent.error(image);
    expect(within(sidebarShop()).getByAltText('Logo of Test Traders').getAttribute('src')).toBe(BUSINESS);
    fireEvent.error(within(sidebarShop()).getByAltText('Logo of Test Traders'));
    expect(within(sidebarShop()).queryByRole('img')).toBeNull();
    expect(sidebarShop().querySelector('.menu-shop-initials')?.textContent).toBe('TT');
  });

  it('shows the business initials for an unbranded business: neither the shop nor the business has a logo', () => {
    brand = { name: 'Test Traders', logoSrc: null, themePrimary: null, themeText: null };
    show('b-one');
    expect(within(sidebarShop()).queryByRole('img')).toBeNull();
    expect(sidebarShop().querySelector('.menu-shop-initials')?.textContent).toBe('TT');
  });

  it('shows All branches with the business logo', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show(ALL_BRANCHES);
    expect((await within(sidebarShop()).findByAltText('Logo of Test Traders')).getAttribute('src')).toBe(BUSINESS);
  });

  it('takes the name and the initials from /me when /branding failed, never the platform name or logo (ADR-068)', () => {
    // brand is PLATFORM_BRAND here, as when /branding failed, has not loaded, or off a business host.
    show('b-one');
    expect(within(sidebarShop()).queryByRole('img')).toBeNull();
    expect(sidebarShop().querySelector('.menu-shop-initials')?.textContent).toBe('TT');
    expect(sidebarShop().querySelector('.menu-shop-name')?.textContent).toBe('Test Traders');
    expect(sidebarShop().textContent).not.toMatch(/Rincoltech|BMS/);
  });

  it('shows an empty tile and a skeleton line while the profile has no business name yet, never a product name', () => {
    show('b-one', { ...me, business_name: undefined } as Me);
    expect(sidebarShop().querySelector('.menu-shop-initials')?.textContent).toBe('');
    expect(sidebarShop().querySelector('.menu-shop-name')).toBeNull();
    expect(sidebarShop().querySelector('.menu-shop-name-skeleton')?.getAttribute('aria-hidden')).toBe('true');
    expect(sidebarShop().textContent).not.toMatch(/Rincoltech|BMS/);
    // The role and the branch still show.
    expect(sidebarShop().querySelector('.menu-shop-branch-name')?.textContent).toBe('Test Shop One (HQ)');
  });

  it('uses the name from /me over a different name in the branding', () => {
    brand = { name: 'Old Shell Name', logoSrc: null, themePrimary: null, themeText: null };
    show('b-one');
    expect(sidebarShop().querySelector('.menu-shop-name')?.textContent).toBe('Test Traders');
    expect(sidebarShop().querySelector('.menu-shop-initials')?.textContent).toBe('TT');
  });

  it("shows the business's own logo when the shop has none, named after the business", async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show('b-one');
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Traders')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(BUSINESS);
    expect(sidebarShop().querySelector('.menu-shop-initials')).toBeNull();
  });

  it('is the only place the business and the branch show from 720px: the top bar keeps Search and the profile button', () => {
    show('b-one');
    const bar = document.querySelector('.staff-bar') as HTMLElement;
    expect(within(bar).queryByRole('combobox')).toBeNull();
    expect(bar.querySelector('.top-title, .top-logo, img')).toBeNull();
    expect(within(bar).getByRole('button', { name: /^Menu for / })).toBeTruthy();
    expect(screen.getAllByRole('combobox', { name: 'Switch branch' })).toHaveLength(1);
    expect(sidebarShop().querySelector('.menu-shop-branch')?.getAttribute('data-tour')).toBe('branch-picker');
    cleanup();
    widthIs(360);
    show('b-one');
    // On a phone the top bar keeps its title and branch picker.
    expect(within(document.querySelector('.staff-bar') as HTMLElement).getByRole('combobox', { name: 'Branch' })).toBeTruthy();
  });

  it('keeps the branch step anchored with no branch in use: Choose a branch with several to choose, the whole top with none', () => {
    show(null);
    expect(sidebarShop().querySelector('.menu-shop-branch-name')?.textContent).toBe('Choose a branch');
    expect(document.querySelectorAll('.side-nav [data-tour="branch-picker"]')).toHaveLength(1);
    expect(sidebarShop().querySelector('.menu-shop-branch')?.getAttribute('data-tour')).toBe('branch-picker');
    cleanup();
    show(null, { ...me, all_branches: false, branches: [] } as Me);
    expect(sidebarShop().querySelector('.menu-shop-branch')).toBeNull();
    expect(sidebarShop().getAttribute('data-tour')).toBe('branch-picker');
  });

  it('puts the branch on a row of its own under the business and the role', () => {
    show('b-one');
    const top = sidebarShop();
    expect(Array.from(top.children).map((c) => c.classList[0])).toEqual(['menu-shop-logo', 'menu-shop-text', 'menu-shop-branch']);
  });

  it('switches branch from the branch at the right, with the choices of the top bar', () => {
    show('b-one');
    const picker = within(sidebarShop()).getByRole('combobox', { name: 'Switch branch' }) as HTMLSelectElement;
    expect(Array.from(picker.options).map((o) => o.textContent)).toEqual(['All branches', 'Test Shop One (HQ)', 'Test Shop Two (KLA)']);
  });
});

describe('the shop in use on a phone, on the More page (issues #461, #539)', () => {
  beforeEach(() => widthIs(360));

  function more(branch: string) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/more'] }) });
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })}>
          <StaffContext.Provider value={{ me, branch, signOut: () => undefined }}>
            <MorePage />
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
  }

  it('shows the shop logo beside the name above Sign out', async () => {
    more('b-two');
    const image = (await screen.findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(screen.getByText('Test Shop Two (KLA)')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
  });

  it('falls back to the business logo for a shop without its own', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    more('b-one');
    expect((await screen.findByAltText('Logo of Test Shop One (HQ)')).getAttribute('src')).toBe(BUSINESS);
  });
});
