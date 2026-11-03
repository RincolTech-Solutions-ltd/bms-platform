import { useEffect, useRef } from 'react';

// A link to one section of a page that loads its content first (Set-up's #reports from the Reports page, issue #783; one
// approval rule, #575): the section exists only once the page has loaded, so the browser's own jump to the hash finds
// nothing on arrival. Once loaded, the page scrolls to the section and puts the cursor on its first control, so a keyboard
// or screen reader user lands where the link pointed too.

const CONTROL = 'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), a[href]';

/** Scrolls to the element named by the address's hash and focuses its first control, once `loaded` is true; once per hash. */
export function useHashSection(loaded: boolean): void {
  const done = useRef<string | null>(null);
  useEffect(() => {
    const id = decodeURIComponent(window.location.hash.slice(1));
    if (!loaded || !id || done.current === id) return;
    const section = document.getElementById(id);
    if (!section) return;
    done.current = id;
    section.scrollIntoView?.({ block: 'start' });
    const control = section.matches(CONTROL) ? section : section.querySelector<HTMLElement>(CONTROL);
    if (control) control.focus({ preventScroll: true });
    else {
      // A section with no control of its own (a heading, a row) takes the focus itself.
      if (!section.hasAttribute('tabindex')) section.setAttribute('tabindex', '-1');
      section.focus({ preventScroll: true });
    }
  }, [loaded]);
}
