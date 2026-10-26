// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { retailAnalytics } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { TourProvider } from '../../../tour/host';
import { StaffContext } from '../context';

// The help page's entry (issue #603): Help, or Ask BMS while the business has it on, just after Home on the More page, in the
// side menu and in feature search, with a New pill on the entry and a dot on the More tab. Both blink until the person opens
// /staff/help once, then stay gone on this device; under reduced motion they stand still, and blocked storage breaks nothing.
// Round 2: an Ask button in the top bar of every staff screen opens /staff/help with the cursor in the question box, and each
// article carries the Ask BMS box at its top.
// The server is a mock and every person is fabricated.

const ask = vi.hoisted(() => ({ status: vi.fn(), ask: vi.fn() }));

vi.mock('../../../api/help', async (original) => {
  const real = await original<typeof import('../../../api/help')>();
  return { ...real, askBms: ask };
});

const { ArticleView, HowDoIPage } = await import('./page');
const { MorePage } = await import('../tab-pages');
const { TabBar } = await import('../tabs');
const { StaffBar, StaffSideNav } = await import('../route');
const { articlesFor } = await import('../../../help/corpus');

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };
const admin = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;

/** A window `px` wide, which asks for reduced motion when `still`. */
function screenIs(px: number, still = false) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: query.includes('reduced-motion') ? still : px <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function show(children: ReactNode, path = '/staff/more') {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  return { router, ...render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch: admin.branches?.[0]?.id ?? null, signOut: () => undefined }}>
          <TourProvider me={admin} saveProgress={false}>
            {children}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  ) };
}

const bar = (props: { showAsk?: boolean } = {}) => <StaffBar me={admin} branch={null} onBranch={() => undefined} onSignOut={() => undefined} {...props} />;

const moreList = () => document.querySelector('[data-tour="more-list"] .row-list') as HTMLElement;
const rowNames = () => within(moreList()).getAllByRole('link').map((a) => a.querySelector('.list-row-title')?.firstChild?.textContent);
const pill = () => document.querySelector('.new-badge');
const dot = () => screen.queryByTestId('new-dot');

beforeEach(() => {
  screenIs(390);
  localStorage.clear();
  ask.status.mockResolvedValue({ enabled: false, revision: 'r' });
  vi.spyOn(retailAnalytics, 'home').mockResolvedValue({ low_stock: 0 } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('the help entry (issue #603)', () => {
  it('is Help just after Home on More while Ask BMS is off, with a blinking New pill, and a New dot on the More tab', async () => {
    show(
      <>
        <MorePage />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    expect(rowNames().slice(0, 2)).toEqual(['Home', 'Help']);
    expect(screen.getByRole('link', { name: 'Help, New' }).getAttribute('href')).toBe('/staff/help');
    expect(pill()?.className).toBe('new-badge new-blink');
    expect(dot()?.className).toBe('new-dot new-blink');
    expect(screen.getByRole('link', { name: 'More, New' })).toBeTruthy();
  });

  it('is named Ask BMS where the business has Ask BMS on, on More and in the side menu', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    screenIs(1280);
    show(
      <>
        <MorePage />
        <StaffSideNav me={admin} />
      </>,
    );
    await waitFor(() => expect(rowNames().slice(0, 2)).toEqual(['Home', 'Ask BMS']));
    const menu = screen.getByRole('navigation', { name: 'Staff areas' });
    const entry = within(menu).getByRole('link', { name: 'Ask BMS, New' });
    expect(entry.getAttribute('href')).toBe('/staff/help');
    expect(within(menu).getAllByRole('link').indexOf(entry)).toBe(1);
  });

  it.each([
    [false, 'help', 'Help'],
    [false, 'ask', 'Help'],
    [true, 'help', 'Ask BMS'],
    [true, 'ask', 'Ask BMS'],
  ])('is found in feature search (Ask BMS on: %s) by typing %s', async (on, word, label) => {
    ask.status.mockResolvedValue({ enabled: on, revision: 'r' });
    show(<MorePage />);
    await waitFor(() => expect(rowNames()[1]).toBe(label));
    fireEvent.click(screen.getByRole('button', { name: 'Search for what you want to do' }));
    const box = (await screen.findByRole('combobox', { name: 'What do you want to do?' })) as HTMLInputElement;
    fireEvent.change(box, { target: { value: word } });
    await waitFor(() => expect(screen.getAllByRole('option').map((o) => o.querySelector('.cb-text')?.firstChild?.textContent)).toContain(label));
  });

  it('goes for good once the person opens /staff/help, on More, the side menu and the More tab at once', async () => {
    screenIs(1280);
    const more = show(
      <>
        <MorePage />
        <StaffSideNav me={admin} />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(document.querySelectorAll('.new-badge')).toHaveLength(2));
    expect(dot()).not.toBeNull();
    show(<HowDoIPage />);
    await waitFor(() => expect(document.querySelectorAll('.new-badge')).toHaveLength(0));
    expect(dot()).toBeNull();
    expect(screen.getByRole('link', { name: 'More' })).toBeTruthy();
    expect(localStorage.getItem('bms.new.ask-bms.seen')).toBe('1');
    more.unmount();
    cleanup();
    show(<MorePage />);
    await waitFor(() => expect(rowNames()[1]).toBe('Help'));
    expect(pill()).toBeNull();
  });

  it('stands still under reduced motion: the pill and the dot show without the blink', async () => {
    screenIs(390, true);
    show(
      <>
        <MorePage />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(pill()).not.toBeNull());
    expect(pill()?.className).toBe('new-badge');
    expect(dot()?.className).toBe('new-dot');
    expect(document.querySelector('.new-blink')).toBeNull();
  });

  it('keeps showing and never breaks when storage refuses to be read or written', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    show(
      <>
        <MorePage />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(pill()).not.toBeNull());
    show(<HowDoIPage />);
    expect(await screen.findByRole('heading', { name: 'How do I...?' })).toBeTruthy();
    expect(pill()).not.toBeNull();
    expect(dot()).not.toBeNull();
  });
});

describe('the Ask button of the top bar (issue #603, round 2)', () => {
  it.each([
    ['Sell', '/staff/retail/sale'],
    ['My stock', '/staff/retail/stock'],
    ['Loans', '/staff/lending'],
  ])('shows on %s as Ask BMS next to Search, opening /staff/help at the question box, with the New dot', async (_screen, path) => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    show(bar(), path);
    const button = await screen.findByRole('link', { name: 'Ask BMS' });
    expect(button.getAttribute('href')).toBe('/staff/help#ask');
    expect(button.textContent).toBe('Ask');
    expect(button.previousElementSibling?.textContent).toBe('Search');
    expect(within(button).getByTestId('new-dot').className).toBe('new-dot new-blink');
  });

  it('says Help while Ask BMS is off for the business', async () => {
    show(bar(), '/staff/retail/sale');
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    const button = screen.getByRole('link', { name: 'Help' });
    expect(button.textContent).toBe('Help');
    expect(screen.queryByRole('link', { name: 'Ask BMS' })).toBeNull();
  });

  it('is not drawn on /staff/help itself, nor while two-step set-up keeps the header minimal', async () => {
    show(bar(), '/staff/help');
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: /^(Ask BMS|Help)$/ })).toBeNull();
    cleanup();
    show(bar({ showAsk: false }), '/staff/retail/sale');
    expect(screen.queryByRole('link', { name: /^(Ask BMS|Help)$/ })).toBeNull();
  });

  it('opens /staff/help#ask, where the cursor lands in the Ask BMS question box', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    const { router } = show(bar(), '/staff/retail/sale');
    fireEvent.click(await screen.findByRole('link', { name: 'Ask BMS' }));
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/help'));
    expect(router.history.location.hash).toBe('#ask');
    cleanup();
    show(<HowDoIPage />, '/staff/help#ask');
    const box = await screen.findByLabelText(/Your question/);
    await waitFor(() => expect(document.activeElement).toBe(box));
  });

  it('puts the cursor in the article search when Ask BMS is off', async () => {
    show(<HowDoIPage />, '/staff/help#ask');
    await waitFor(() => expect(document.activeElement?.id).toBe('help-q'));
    expect(screen.queryByLabelText(/Your question/)).toBeNull();
  });

  it('loses its dot once the person has opened /staff/help', async () => {
    show(bar(), '/staff/retail/stock');
    const button = await screen.findByRole('link', { name: 'Help' });
    expect(within(button).queryByTestId('new-dot')).not.toBeNull();
    show(<HowDoIPage />, '/staff/help');
    await waitFor(() => expect(within(button).queryByTestId('new-dot')).toBeNull());
  });

  it('shows the Ask BMS box at the top of an article while Ask BMS is on, and not while it is off', async () => {
    const [article] = articlesFor(admin);
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    show(<ArticleView articleId={article!.id} />, `/staff/help/${article!.id}`);
    const box = await screen.findByRole('form', { name: 'Ask BMS' });
    expect(box.compareDocumentPosition(screen.getByRole('heading', { level: 1 })) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
    cleanup();
    ask.status.mockClear();
    ask.status.mockResolvedValue({ enabled: false, revision: 'r' });
    show(<ArticleView articleId={article!.id} />, `/staff/help/${article!.id}`);
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByRole('form', { name: 'Ask BMS' })).toBeNull();
  });
});
