import { useSyncExternalStore } from 'react';
import { useMedia } from '../../components/side-menu';

// The new-feature badge (issue #603): a small New pill beside a menu entry, and a dot on the tab that leads to it, until this
// person opens the feature once. Then this device remembers it in localStorage and both are gone for good. Every read and write
// of storage is in try/catch: where storage is blocked the badge keeps showing and nothing breaks. Both blink by opacity only,
// the one looping blink the design guard allows, which the owner asked for; under reduced motion they stand still.

const keyOf = (feature: string) => `bms.new.${feature}.seen`;
const listeners = new Set<() => void>();

function seen(feature: string): boolean {
  try {
    return localStorage.getItem(keyOf(feature)) === '1';
  } catch {
    return false;
  }
}

/** Remembers on this device that the person has opened `feature`, which hides its badges everywhere at once. */
export function markFeatureSeen(feature: string) {
  if (seen(feature)) return;
  try {
    localStorage.setItem(keyOf(feature), '1');
  } catch {
    return;
  }
  listeners.forEach((notify) => notify());
}

function subscribe(notify: () => void) {
  listeners.add(notify);
  // Another tab that opened the feature hides the badge here too.
  window.addEventListener('storage', notify);
  return () => {
    listeners.delete(notify);
    window.removeEventListener('storage', notify);
  };
}

/** True until this person has opened `feature` on this device. */
export function useIsNew(feature: string): boolean {
  return useSyncExternalStore(subscribe, () => !seen(feature), () => false);
}

/** The badge's classes: the blink only while the person has not asked for reduced motion. */
function useBlink(base: string): string {
  return useMedia('(prefers-reduced-motion: reduce)') ? base : `${base} new-blink`;
}

/** The New pill, read out as ", New" after the entry's name. */
export function NewBadge({ feature }: { feature: string }) {
  const fresh = useIsNew(feature);
  const className = useBlink('new-badge');
  if (!fresh) return null;
  return (
    <span className={className}>
      <span className="visually-hidden">, </span>New
    </span>
  );
}

/** The dot on a tab's icon. It is hidden from screen readers: the tab says New in its own name (useIsNew). */
export function NewDot({ feature }: { feature: string }) {
  const fresh = useIsNew(feature);
  const className = useBlink('new-dot');
  return fresh ? <span className={className} aria-hidden="true" data-testid="new-dot" /> : null;
}
