import { useEffect, useRef, useState, type ReactNode } from 'react';

// The one loading state (#520, docs/ui-rules.md rule 14): nothing at all for the first second, so a quick load never flashes,
// then grey rows in the shape of the list until it arrives. Once the content has been on the screen it stays there through
// a later reload: the skeleton never covers what the person was reading. Pass `loading` (a query's isPending) and the content
// as children. The look is .skeleton-rows in src/app/ui/kit.css.

/** How long a load may take before the skeleton shows, in ms. */
export const SKELETON_DELAY = 1000;

export function Skeleton({ loading, rows = 3, label = 'Loading', children }: { loading: boolean; rows?: number; label?: string; children?: ReactNode }) {
  const shown = useRef(false);
  const [late, setLate] = useState(false);
  if (!loading) shown.current = true;
  const waiting = loading && !shown.current;

  useEffect(() => {
    if (!waiting) return;
    setLate(false);
    const timer = window.setTimeout(() => setLate(true), SKELETON_DELAY);
    return () => window.clearTimeout(timer);
  }, [waiting]);

  if (!waiting) return <>{children}</>;
  if (!late) return null;
  return (
    <div className="skeleton-rows" role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton-row" aria-hidden="true">
          <span className="skeleton-bar" />
          <span className="skeleton-bar skeleton-bar-short" />
        </div>
      ))}
    </div>
  );
}
