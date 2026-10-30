import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, problemOf } from '../../api/client';
import type { components } from '../../api/schema';
import { BusyButton } from '../../app/ui/BusyButton';
import { Hint } from '../../app/ui/Hint';
import { businessZone } from '../../api/retail';
import { ShortNameField, shortNameProblem } from '../onboarding/short-name';
import { sayDone } from './retail/toast';

// The shop's short link in Business set-up (issue #708, ADR-108): rincoltech.com/<short name> with a Copy button, the old links that
// still forward, and for the Owner, Change short name with the same as-typed check as the sign-up. The short name is also the app's
// address, so a change moves the sign-in page too; the old link forwards to the new address for 90 days.

type ShortLink = components['schemas']['ShortLink'];

export const SHORT_LINK_QUERY = ['settings', 'short-link'] as const;

async function fetchShortLink(): Promise<ShortLink> {
  const { data, error } = await api.GET('/api/v1/settings/short-link');
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Your short link could not be loaded.');
  return data;
}

async function checkName(name: string) {
  const { data, error } = await api.GET('/api/v1/settings/short-link/check', { params: { query: { name } } });
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not check the short name.');
  return data;
}

function day(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: businessZone() });
}

/** Copies the link; an older phone browser without clipboard permission gets the field selected instead. */
function CopyLink({ url, label }: { url: string; label: string }) {
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
      <label htmlFor="short-link-url">{label}</label>
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

export function ShortLinkCard({ isOwner, canManage, load = fetchShortLink }: { isOwner: boolean; canManage: boolean; load?: () => Promise<ShortLink> }) {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: SHORT_LINK_QUERY, queryFn: load });
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState('');
  const [problem, setProblem] = useState<string | undefined>();
  const [offers, setOffers] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [moved, setMoved] = useState<ShortLink | null>(null);

  const link = query.data;
  if (query.error) return <p role="alert">{(query.error as Error).message}</p>;
  if (!link) return <p className="loading">Loading</p>;

  async function save() {
    const typed = name.trim();
    const shape = shortNameProblem(typed);
    if (shape) return setProblem(shape);
    setBusy(true);
    setProblem(undefined);
    try {
      const { data, error } = await api.PUT('/api/v1/settings/short-link', { body: { short_name: typed } });
      if (error || !data) {
        const p = problemOf(error) as ReturnType<typeof problemOf> & { suggestions?: string[] };
        setProblem(p.errors?.[0]?.message ?? p.detail ?? 'Could not change the short name.');
        setOffers(p.suggestions ?? []);
        return;
      }
      sayDone(`Short link changed to ${data.link ?? typed}`);
      setMoved(data);
      setEditing(false);
      queryClient.setQueryData(SHORT_LINK_QUERY, data);
    } finally {
      setBusy(false);
    }
  }

  return (
    <fieldset>
      <legend>Short link</legend>
      <p>
        Customers type <strong>{link.link}</strong> to reach your shop. Put it on your receipts, posters and WhatsApp status.
      </p>
      <CopyLink url={link.url ?? ''} label="Your short link" />
      {(link.forwarding ?? []).map((old) => (
        <p key={old.short_name}>
          The old link {old.link} still forwards here until {day(old.forwards_until)}.
        </p>
      ))}
      {moved && (
        <div role="status" className="alert alert-info stack">
          <p>
            Your short link is now {moved.link}. Your app moved with it: you and your staff sign in at the new address from now on, and
            an app installed on a phone must be added again from there.
          </p>
          <a className="btn" href={moved.sign_in_url}>
            Open the new sign-in page
          </a>
        </div>
      )}
      {canManage && !isOwner && <Hint full>Only the owner of the business changes the short name.</Hint>}
      {isOwner && !editing && !moved && (
        <button type="button" onClick={() => { setEditing(true); setName(link.short_name ?? ''); setProblem(undefined); setOffers([]); }}>
          Change short name
        </button>
      )}
      {isOwner && editing && (
        <div className="stack">
          <ShortNameField
            id="new-short-name"
            label="New short name"
            value={name}
            onChange={(v) => { setName(v); setProblem(undefined); setOffers([]); }}
            check={checkName}
            current={link.short_name}
            problem={problem}
            suggestions={offers}
          />
          <Hint full>
            The short name is also your app's address. After the change you and your staff sign in at the new address. The old link keeps
            working for 90 days, and nobody else can take it for 90 days after that.
          </Hint>
          <div className="cluster">
            <BusyButton busy={busy} className="rt-primary" disabled={name.trim() === link.short_name} onClick={() => void save()}>
              Change it
            </BusyButton>
            <button type="button" /* busy-exempt: only closes the form */ onClick={() => setEditing(false)}>
              Keep {link.short_name}
            </button>
          </div>
        </div>
      )}
    </fieldset>
  );
}
