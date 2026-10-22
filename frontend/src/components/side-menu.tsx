import { Link } from '@tanstack/react-router';
import { type KeyboardEvent, type ReactNode, type TouchEvent, useEffect, useId, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { useTour } from '../tour/host';
import { icons } from './icons';

// The phone side menu (issue #455, docs/ui/design-system.md "Navigation"): below 720px the row of area tabs gives way to a menu
// button at the top left of the bar that opens a drawer from the left. The drawer lists the same links the tabs would, each with an
// icon, the current one marked with aria-current, then the person's name and Sign out. It closes on the backdrop, Escape, a left
// swipe or a chosen link, keeps focus inside while open and gives it back to the menu button. A tour step that points at one of its
// data-tour anchors opens it for as long as the step is shown, without taking focus from the tour card.

const PHONE = '(max-width: 719px)';
const subscribePhone = (notify: () => void) => {
  if (typeof window === 'undefined' || !window.matchMedia) return () => undefined;
  const query = window.matchMedia(PHONE);
  query.addEventListener('change', notify);
  return () => query.removeEventListener('change', notify);
};

/** True below 720px, the design system's phone width. False when rendered without a window. */
export function useIsPhone(): boolean {
  return useSyncExternalStore(
    subscribePhone,
    () => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(PHONE).matches,
    () => false,
  );
}

export interface MenuLink {
  to: string;
  label: string;
  icon: ReactNode;
  /** The data-tour id a tour step lights up. */
  tour?: string;
  /** Marked current only on its own page, not on the pages under it. */
  exact?: boolean;
  /** Links shown as a group under this one in the side menu, such as the retail shortcuts. */
  group?: ReactNode;
}

const FOCUSABLE = 'a[href], button:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
const SWIPE = 50;

/** The tabs on a wider screen: the same links, unchanged since before the side menu. */
export function AreaTabs({ label, tour, links }: { label: string; tour?: string; links: MenuLink[] }) {
  return (
    <nav className="tabs" aria-label={label} data-tour={tour}>
      {links.map((l) => (
        <Link key={l.to} to={l.to as never} activeOptions={l.exact ? { exact: true } : undefined} data-tour={l.tour}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}

/** The menu button and its drawer. `label` names the list of links, `tour` is its data-tour id, `tours` the ids inside the groups. */
export function SideMenu({ label, tour, links, name, onSignOut, tours = [] }: { label: string; tour?: string; links: MenuLink[]; name?: string; onSignOut: () => void; tours?: string[] }) {
  const [open, setOpen] = useState(false);
  const heading = useId();
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const swipe = useRef<{ x: number; y: number } | null>(null);
  const { running, target } = useTour();
  const anchors = [tour, ...links.map((l) => l.tour), ...tours].filter(Boolean);
  // Opened by a tour step: shown at once, with no focus moved or kept, so the tour card keeps the keyboard.
  const shownForTour = target !== undefined && anchors.includes(target);
  const shown = open || shownForTour;

  function close() {
    setOpen(false);
    window.setTimeout(() => button.current?.focus(), 0);
  }

  // A tour that starts while the menu is open takes over; the menu waits for the steps that point into it.
  useEffect(() => {
    if (running) setOpen(false);
  }, [running]);

  useEffect(() => {
    if (!open) return;
    panel.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    document.body.classList.add('side-menu-open');
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.body.classList.remove('side-menu-open');
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!open || event.key !== 'Tab') return;
    const controls = Array.from(panel.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    if (controls.length === 0) return;
    const at = controls.indexOf(document.activeElement as HTMLElement);
    const next = event.shiftKey ? (at <= 0 ? controls.length - 1 : at - 1) : at === controls.length - 1 ? 0 : at + 1;
    event.preventDefault();
    controls[next]?.focus();
  }

  function onTouchEnd(event: TouchEvent<HTMLDivElement>) {
    const from = swipe.current;
    const touch = event.changedTouches[0];
    swipe.current = null;
    if (!open || !from || !touch) return;
    const dx = touch.clientX - from.x;
    if (dx < -SWIPE && Math.abs(dx) > Math.abs(touch.clientY - from.y)) close();
  }

  const drawer = (
    <div className={shownForTour && !open ? 'side-menu side-menu-instant' : 'side-menu'} hidden={!shown}>
      <div className="side-menu-backdrop" onClick={open ? close : undefined} />
      <div
        ref={panel}
        className="side-menu-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={heading}
        onKeyDown={onKeyDown}
        onClick={(event) => {
          if (open && event.target instanceof Element && event.target.closest('a')) close();
        }}
        onTouchStart={(event) => {
          const touch = event.touches[0];
          swipe.current = touch ? { x: touch.clientX, y: touch.clientY } : null;
        }}
        onTouchEnd={onTouchEnd}
      >
        <div className="side-menu-head">
          <h2 id={heading} className="side-menu-title">Menu</h2>
          <button type="button" className="btn-ghost menu-button" aria-label="Close menu" onClick={close}>
            {icons.close}
          </button>
        </div>
        <nav className="side-menu-links" aria-label={label} data-tour={tour}>
          <ul>
            {links.map((l) => (
              <li key={l.to}>
                <Link to={l.to as never} activeOptions={l.exact || l.group ? { exact: true } : undefined} data-tour={l.tour}>
                  {l.icon}
                  <span>{l.label}</span>
                </Link>
                {l.group}
              </li>
            ))}
          </ul>
        </nav>
        <div className="side-menu-foot">
          <span className="staff-user">
            <span className="avatar">{icons.user}</span>
            <span className="staff-user-name">{name}</span>
          </span>
          <button type="button" className="btn-ghost" onClick={onSignOut}>
            {icons.signOut}
            <span>Sign out</span>
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <>
      <button ref={button} type="button" className="btn-ghost menu-button" aria-label="Menu" aria-haspopup="dialog" aria-expanded={shown} onClick={() => setOpen(true)}>
        {icons.menu}
      </button>
      {/* In the body, because the staff bar clips what is drawn inside it (its full-bleed band is a clip-path). */}
      {createPortal(drawer, document.body)}
    </>
  );
}
