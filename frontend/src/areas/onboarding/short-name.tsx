import { useEffect, useRef, useState } from 'react';

// The short name of a shop's link, rincoltech.com/<short name>, which is also its address (issue #708, ADR-108). The sign-up field
// checks the name as it is typed and, when the name is taken or kept for the website, offers free names to pick with one tap, the
// one with the shop's town or area first. The server checks again on save. The link's host comes from the server.

/** A short name's shape: 3 to 20 lower case letters and digits, a single hyphen may join two words. */
export const SHORT_NAME = /^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,18}[a-z0-9]$/;

/** What the server says of a name: free, or why not with free suggestions. */
export interface ShortNameCheck {
  name?: string;
  available?: boolean;
  problem?: string | null;
  message?: string | null;
  suggestions?: string[];
}

/** The plain words for a short name that cannot be right, before asking the server; null when its shape is fine. */
export function shortNameProblem(raw: string): string | null {
  const name = raw.trim().toLowerCase();
  if (name === '') return 'Choose a short name for your link.';
  if (!SHORT_NAME.test(name)) return 'Use 3 to 20 lower case letters and digits. A hyphen may join two words.';
  return null;
}

/** A first short name from the business name: its first word, letters and digits only, at most 20; empty when too short. */
export function shortNameFrom(businessName: string): string {
  const word =
    businessName
      .normalize('NFD')
      .replace(/\p{M}/gu, '')
      .toLowerCase()
      .split(/[^a-z0-9]+/)
      .find((w) => w.length > 0) ?? '';
  const name = word.slice(0, 20);
  return name.length >= 3 ? name : '';
}

/** Lower case, spaces made hyphens, anything else dropped: what a person types becomes a name the server can check. */
export function cleanShortName(typed: string): string {
  return typed.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '');
}

const WAIT_MS = 400;

export interface ShortNameFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (name: string) => void;
  /** Asks the server; a failure shows nothing, and the save checks again. */
  check: (name: string) => Promise<ShortNameCheck>;
  /** A problem from the form or the server's answer to a save. */
  problem?: string;
  /** Free names from the server's answer to a save. */
  suggestions?: string[];
  /** The name the shop has now (Settings): not checked, nothing said. */
  current?: string;
  /** The short link host the server gave (`short_link_host` of the rule); empty until it has loaded. */
  host: string;
}

export function ShortNameField({ id, label, value, onChange, check, problem, suggestions, current, host }: ShortNameFieldProps) {
  const prefix = host ? `${host}/` : '';
  const [answer, setAnswer] = useState<ShortNameCheck | null>(null);
  const asked = useRef(0);
  // The latest check without making a new function from the parent ask again.
  const ask = useRef(check);
  ask.current = check;

  useEffect(() => {
    const name = value.trim();
    setAnswer(null);
    if (name === '' || name === current || shortNameProblem(name)) return;
    const ticket = ++asked.current;
    const timer = setTimeout(() => {
      ask
        .current(name)
        .then((result) => {
          if (ticket === asked.current) setAnswer(result);
        })
        .catch(() => undefined);
    }, WAIT_MS);
    return () => clearTimeout(timer);
  }, [value, current]);

  const shown = problem ?? (answer && !answer.available ? (answer.message ?? undefined) : undefined);
  const free = answer?.available ? answer.name : null;
  const offers = (answer && !answer.available ? answer.suggestions : suggestions) ?? [];
  const describedBy = [shown ? `${id}-problem` : null, `${id}-hint`].filter(Boolean).join(' ');

  return (
    <div className="stack">
      <label htmlFor={id}>{label}</label>
      <div className="cluster">
        {prefix && <span aria-hidden="true">{prefix}</span>}
        <input
          id={id}
          value={value}
          onChange={(e) => onChange(cleanShortName(e.target.value))}
          maxLength={20}
          autoCapitalize="none"
          autoComplete="off"
          spellCheck={false}
          inputMode="url"
          required
          aria-invalid={Boolean(shown)}
          aria-describedby={describedBy}
        />
      </div>
      <span id={`${id}-hint`} className="hint">
        Your customers type {prefix}
        {value.trim() || 'yourshop'} to reach your shop. 3 to 20 letters and digits.
      </span>
      {shown && (
        <span id={`${id}-problem`} className="field-error">
          {shown}
        </span>
      )}
      {free && (
        <span role="status" className="hint">
          {prefix}
          {free} is free.
        </span>
      )}
      {offers.length > 0 && (
        <div className="stack" role="group" aria-label="Free short names">
          <span>Free names you can use:</span>
          <div className="cluster">
            {offers.map((s) => (
              <button key={s} type="button" onClick={() => onChange(s)}>
                {s}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
