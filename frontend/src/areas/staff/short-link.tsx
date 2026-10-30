import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, problemOf } from '../../api/client';
import type { components } from '../../api/schema';

// The shop's short link in Business set-up (issue #708, ADR-108): rincoltech.com/<short name> with a Copy button. The short name
// was chosen at sign-up and is also the app's address, so it stays the same; Rincol Tech changes it on request.

type ShortLink = components['schemas']['ShortLink'];

export const SHORT_LINK_QUERY = ['settings', 'short-link'] as const;

async function fetchShortLink(): Promise<ShortLink> {
  const { data, error } = await api.GET('/api/v1/settings/short-link');
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Your short link could not be loaded.');
  return data;
}

/** Copies the link; an older phone browser without clipboard permission gets the field selected instead. */
function CopyLink({ url }: { url: string }) {
  const field = useRef<HTMLInputElement>(null);
  const [said, setSaid] = useState<string | null>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setSaid('Copied. Paste it into a message or on your poster.');
      return;
    } catch {
      // No clipboard permission: select the link so the person can copy it by hand.
    }
    field.current?.focus();
    field.current?.select();
    setSaid('Your phone would not copy it. The link is selected: press and hold, then choose Copy.');
  }

  return (
    <div className="stack">
      <label htmlFor="short-link-url">Your short link</label>
      <div className="cluster">
        <input id="short-link-url" ref={field} readOnly value={url} spellCheck={false} onFocus={(e) => e.currentTarget.select()} />
        <button type="button" className="rt-primary" onClick={() => void copy()}>
          Copy
        </button>
      </div>
      {said && <p role="status">{said}</p>}
    </div>
  );
}

export function ShortLinkCard({ load = fetchShortLink }: { load?: () => Promise<ShortLink> }) {
  const query = useQuery({ queryKey: SHORT_LINK_QUERY, queryFn: load });
  const link = query.data;
  if (query.error) return <p role="alert">{(query.error as Error).message}</p>;
  if (!link) return <p className="loading">Loading</p>;
  return (
    <fieldset>
      <legend>Short link</legend>
      <p>
        Customers type <strong>{link.link}</strong> to reach your shop. Put it on your receipts, posters and WhatsApp status.
      </p>
      <CopyLink url={link.url ?? ''} />
    </fieldset>
  );
}
