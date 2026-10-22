// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ICON_VERSION_KEY, NewIconNote, NewLogoNotice } from './NewIcon';

// A new logo on the home screen (issue #453, ADR-066): an iPhone keeps the icon it took when the app was added, so the installed app
// on an iPhone or iPad says once per new icon that the app must be added again. Never on Android, on a desktop or in a browser tab;
// the first run only remembers the version; Done remembers the new one; storage that throws never breaks the page.

const REAL_AGENT = navigator.userAgent;
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1';
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/129.0 Mobile Safari/537.36';
const OLD = '/api/v1/branding/logo?v=doc-1|#0D5C75';
const NEW = '/api/v1/branding/logo?v=doc-2|#0D5C75';

function device({ agent = IPHONE, installed = true } = {}) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: installed, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
  );
  Object.defineProperty(navigator, 'userAgent', { value: agent, configurable: true });
}

const card = () => screen.queryByTestId('new-logo-card');

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  Object.defineProperty(navigator, 'userAgent', { value: REAL_AGENT, configurable: true });
});

describe('the new logo card', () => {
  it('stays away on the first run and only remembers the version', () => {
    device();
    render(<NewLogoNotice version={OLD} />);
    expect(card()).toBeNull();
    expect(localStorage.getItem(ICON_VERSION_KEY)).toBe(OLD);
  });

  it('shows in the installed app on an iPhone once the version changes, with the steps', () => {
    device();
    localStorage.setItem(ICON_VERSION_KEY, OLD);
    render(<NewLogoNotice version={NEW} />);
    expect(screen.getByRole('heading', { name: 'Your business has a new logo' })).toBeTruthy();
    expect(card()?.textContent).toContain('press and hold BMS, tap Remove App, then open the link in Safari and add it again');
    expect(screen.getByTestId('ios-install-steps')).toBeTruthy();
  });

  it('shows after a business with no logo uploads one', () => {
    device();
    localStorage.setItem(ICON_VERSION_KEY, '');
    render(<NewLogoNotice version={NEW} />);
    expect(card()).toBeTruthy();
  });

  it('waits while the branding is unknown', () => {
    device();
    localStorage.setItem(ICON_VERSION_KEY, OLD);
    render(<NewLogoNotice version={null} />);
    expect(card()).toBeNull();
    expect(localStorage.getItem(ICON_VERSION_KEY)).toBe(OLD);
  });

  it('is never shown in a browser tab on an iPhone', () => {
    device({ installed: false });
    localStorage.setItem(ICON_VERSION_KEY, OLD);
    render(<NewLogoNotice version={NEW} />);
    expect(card()).toBeNull();
    expect(localStorage.getItem(ICON_VERSION_KEY)).toBe(OLD);
  });

  it('is never shown on Android or a desktop, even installed', () => {
    for (const agent of [ANDROID, REAL_AGENT]) {
      device({ agent });
      localStorage.setItem(ICON_VERSION_KEY, OLD);
      render(<NewLogoNotice version={NEW} />);
      expect(card()).toBeNull();
      cleanup();
    }
  });

  it('goes for good after Done, for that version', () => {
    device();
    localStorage.setItem(ICON_VERSION_KEY, OLD);
    render(<NewLogoNotice version={NEW} />);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(card()).toBeNull();
    expect(localStorage.getItem(ICON_VERSION_KEY)).toBe(NEW);
    cleanup();
    render(<NewLogoNotice version={NEW} />);
    expect(card()).toBeNull();
  });

  it('renders without the card when storage throws', () => {
    device();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    render(
      <main>
        <p>Page</p>
        <NewLogoNotice version={NEW} />
      </main>,
    );
    expect(screen.getByText('Page')).toBeTruthy();
    expect(card()).toBeNull();
  });

  it('still hides on Done when the version cannot be remembered', () => {
    device();
    localStorage.setItem(ICON_VERSION_KEY, OLD);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    render(<NewLogoNotice version={NEW} />);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(card()).toBeNull();
  });
});

describe('the set-up note', () => {
  it('reveals the existing iPhone steps on a tap', () => {
    render(<NewIconNote />);
    expect(screen.queryByTestId('ios-install-steps')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show me how on an iPhone' }));
    expect(screen.getByTestId('ios-install-steps')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Show me how on an iPhone' })).toBeNull();
  });
});
