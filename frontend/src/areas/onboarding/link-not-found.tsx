import { Link, createLazyRoute } from '@tanstack/react-router';
import { SHORT_NAME } from './short-name';

// Where a short link lands when no shop uses it (issue #708, ADR-108): a name never taken, mistyped, or of a shop that is closed.
// On the platform host, no sign-in. The name in the address is shown only when it has the shape of a short name.

/** The name from `?name=`, or null when it is missing or not a short name. */
export function linkNameFrom(search: string): string | null {
  const name = (new URLSearchParams(search).get('name') ?? '').trim().toLowerCase();
  return name.length <= 63 && /^[a-z0-9-]{3,63}$/.test(name) ? name : null;
}

export function LinkNotFound({ name }: { name: string | null }) {
  return (
    <main className="card auth-card stack">
      <h1>No shop uses this link</h1>
      <p>
        {name ? (
          <>
            No shop uses the short link <strong>{name}</strong> now.
          </>
        ) : (
          'No shop uses this link now.'
        )}{' '}
        Check the spelling with the shop, or ask them for their link again.
      </p>
      <p className="cluster">
        <Link className="btn" to="/find-sign-in">
          Staff: find your sign-in page
        </Link>
        <Link className="btn" to="/sign-up">
          Start using BMS
        </Link>
      </p>
      {name && !SHORT_NAME.test(name) && <p className="hint">A short link is 3 to 20 letters and digits after the slash.</p>}
    </main>
  );
}

export const Route = createLazyRoute('/link-not-found')({ component: () => <LinkNotFound name={linkNameFrom(window.location.search)} /> });
