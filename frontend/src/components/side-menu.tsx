import { Link } from '@tanstack/react-router';
import { type KeyboardEvent, type ReactNode, type TouchEvent, createContext, useContext, useEffect, useId, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { useTour } from '../tour/host';
import { icons } from './icons';

// The side menu (issue #455, docs/ui/design-system.md "Navigation"): one list of areas, each with an icon, on every screen size.
// From 720px it is a sidebar at the left of the page (SideNav) that can fold to its icons; below 720px a menu button at the top left
// of the bar opens the same list in a drawer from the left (SideMenu). Both draw MenuLinks, so the items, groups and the current
// one (aria-current) cannot drift apart. The drawer closes on the backdrop, Escape, a left swipe or a chosen link, keeps focus
// inside while open and gives it back to the menu button. A tour step that points at one of the data-tour anchors opens the drawer,
// or unfolds the folded sidebar, for as long as the step is shown, without taking focus from the tour card.

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

// True inside the folded sidebar, so a group's links fold with the items above them.
const Folded = createContext(false);

/** One link of the side menu: its icon and label, the label kept as the accessible name and shown as a tooltip when folded. */
export function MenuItemLink({ to, label, icon, tour, exact }: { to: string; label: string; icon: ReactNode; tour?: string; exact?: boolean }) {
  const folded = useContext(Folded);
  return (
    <Link to={to as never} activeOptions={exact ? { exact: true } : undefined} data-tour={tour} title={folded ? label : undefined}>
      {icon}
      <span className={folded ? 'visually-hidden' : undefined}>{label}</span>
    </Link>
  );
}

/** The list of areas both the sidebar and the drawer draw, the groups under their item. */
function MenuLinks({ label, tour, links, id, folded = false }: { label: string; tour?: string; links: MenuLink[]; id?: string; folded?: boolean }) {
  return (
    <Folded.Provider value={folded}>
      <nav id={id} className="side-menu-links" aria-label={label} data-tour={tour}>
        <ul>
          {links.map((l) => (
            <li key={l.to}>
              <MenuItemLink to={l.to} label={l.label} icon={l.icon} tour={l.tour} exact={l.exact || !!l.group} />
              {l.group}
            </li>
          ))}
        </ul>
      </nav>
    </Folded.Provider>
  );
}

const FOLDED_KEY = 'bms.sidebar';

function readFolded(): boolean {
  try {
    return window.localStorage.getItem(FOLDED_KEY) === 'folded';
  } catch {
    return false;
  }
}

function keepFolded(folded: boolean) {
  try {
    if (folded) window.localStorage.setItem(FOLDED_KEY, 'folded');
    else window.localStorage.removeItem(FOLDED_KEY);
  } catch {
    // Storage blocked (a private window): the choice lasts until the page is reloaded.
  }
}

/** The sidebar from 720px: the same list as the drawer, about 240px wide, or 64px of icons when folded (kept per device). */
export function SideNav({ label, tour, links, tours = [] }: { label: string; tour?: string; links: MenuLink[]; tours?: string[] }) {
  const [folded, setFolded] = useState(readFolded);
  const listId = useId();
  const { target } = useTour();
  const anchors = [tour, ...links.map((l) => l.tour), ...tours].filter(Boolean);
  // Unfolded while a tour step points into it, so the step can show the label it talks about.
  const shownFolded = folded && !(target !== undefined && anchors.includes(target));

  function toggle() {
    keepFolded(!folded);
    setFolded(!folded);
  }

  return (
    <aside className={shownFolded ? 'side-nav side-nav-folded' : 'side-nav'}>
      <button
        type="button"
        className="btn-ghost side-nav-toggle"
        aria-label="Show menu labels"
        aria-expanded={!shownFolded}
        aria-controls={listId}
        title={shownFolded ? 'Show menu labels' : 'Hide menu labels'}
        onClick={toggle}
      >
        {icons.menu}
      </button>
      <MenuLinks id={listId} label={label} tour={tour} links={links} folded={shownFolded} />
    </aside>
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
        <MenuLinks label={label} tour={tour} links={links} />
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
