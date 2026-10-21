import { useEffect, useSyncExternalStore } from 'react';

// The theme choice (issue #429): System, Light or Dark, kept on this device and set on <html> as
// data-theme="light" or "dark", which theme.css reads. public/theme-init.js does the same before the
// first paint; the two share the key and the colours, and theme-mode.test.ts holds them together.

export const THEME_KEY = 'bms.theme';
export const THEME_CHOICES = ['system', 'light', 'dark'] as const;
export type ThemeChoice = (typeof THEME_CHOICES)[number];
export type Theme = 'light' | 'dark';

/** The browser's and the installed app's theme colour: the brand blue of the manifest, and the dark surface. */
export const THEME_COLOURS: Record<Theme, string> = { light: '#0077b6', dark: '#0e141b' };

const DARK_QUERY = '(prefers-color-scheme: dark)';
const listeners = new Set<() => void>();

export function parseChoice(value: string | null | undefined): ThemeChoice {
  return value === 'light' || value === 'dark' ? value : 'system';
}

export function readChoice(): ThemeChoice {
  try {
    return parseChoice(window.localStorage.getItem(THEME_KEY));
  } catch {
    return 'system';
  }
}

export function systemPrefersDark(): boolean {
  try {
    return window.matchMedia(DARK_QUERY).matches;
  } catch {
    return false;
  }
}

export function resolveTheme(choice: ThemeChoice, prefersDark: boolean): Theme {
  return choice === 'system' ? (prefersDark ? 'dark' : 'light') : choice;
}

/** Puts the theme on <html> and its colour on the theme-color meta tag. */
export function applyTheme(choice: ThemeChoice, prefersDark = systemPrefersDark()): Theme {
  const theme = resolveTheme(choice, prefersDark);
  const root = document.documentElement;
  root.setAttribute('data-theme', theme);
  root.setAttribute('data-theme-choice', choice);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOURS[theme]);
  return theme;
}

/** Remembers the choice on this device (System forgets it) and applies it. */
export function saveChoice(choice: ThemeChoice): void {
  try {
    if (choice === 'system') window.localStorage.removeItem(THEME_KEY);
    else window.localStorage.setItem(THEME_KEY, choice);
  } catch {
    // Storage can be blocked; the choice still holds for this visit.
  }
  applyTheme(choice);
  listeners.forEach((notify) => notify());
}

function subscribe(notify: () => void): () => void {
  listeners.add(notify);
  const onStorage = (event: StorageEvent) => {
    if (event.key === THEME_KEY || event.key === null) {
      applyTheme(readChoice());
      notify();
    }
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(notify);
    window.removeEventListener('storage', onStorage);
  };
}

/** The saved choice, re-applied when the system setting changes while the choice is System. */
export function useThemeChoice(): [ThemeChoice, (choice: ThemeChoice) => void] {
  const choice = useSyncExternalStore(subscribe, readChoice, () => 'system' as ThemeChoice);
  useEffect(() => {
    applyTheme(choice);
    if (choice !== 'system') return;
    let query: MediaQueryList;
    try {
      query = window.matchMedia(DARK_QUERY);
    } catch {
      return;
    }
    const onChange = () => applyTheme('system');
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, [choice]);
  return [choice, saveChoice];
}
