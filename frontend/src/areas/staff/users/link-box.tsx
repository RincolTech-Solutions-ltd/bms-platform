import { useRef, useState } from 'react';
import { expiryWords, whatsappLink } from './state';

// The one-time invitation link (issue #319; FR-IAM-01). The server returns it once, to the inviting administrator, and keeps only a
// hash of it. This box is the only place it is shown: it lives in the screen's own state (never in a query cache), a Copy button
// and a WhatsApp button carry it to the person, and it is gone for good when the box is closed.

export interface ShownLink {
  name: string;
  phone: string | null;
  url: string;
  expiresAt?: string;
  /** "invited" for a new person, "again" for a link made after a first one stopped working. */
  kind: 'invited' | 'again';
}

export function LinkBox({ link, business, onDone, doneLabel = 'I have sent it' }: { link: ShownLink; business: string; onDone: () => void; doneLabel?: string }) {
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null);
  const field = useRef<HTMLInputElement>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(link.url);
      setCopied('yes');
    } catch {
      // No clipboard permission (an older phone browser): select the link so it can be copied by hand.
      field.current?.select();
      setCopied('no');
    }
  }

  return (
    <section className="rt-card stack" aria-labelledby="link-title" data-tour="staff-link">
      <h2 id="link-title">{link.kind === 'invited' ? `${link.name} is invited` : `New link for ${link.name}`}</h2>
      <p className="alert alert-warning" role="note">
        This link is shown only now. Send it to {link.name} yourself: they open it to choose their password. It works once and stops
        working on <strong>{expiryWords(link.expiresAt)}</strong>. If it is lost, make a new one from the staff list.
      </p>
      <label htmlFor="invite-link">Invitation link</label>
      <input id="invite-link" ref={field} readOnly value={link.url} onFocus={(e) => e.currentTarget.select()} spellCheck={false} />
      <p className="cluster">
        <button type="button" className="rt-primary" onClick={() => void copy()}>Copy the link</button>
        <a className="btn" href={whatsappLink({ phone: link.phone, name: link.name, business, url: link.url, expiresAt: link.expiresAt })} target="_blank" rel="noopener noreferrer">
          Send on WhatsApp
        </a>
      </p>
      {copied === 'yes' && <p role="status" className="alert alert-success">Copied. Paste it into a message.</p>}
      {copied === 'no' && <p role="status" className="alert alert-info">Your phone would not copy it. The link is selected: press and hold, then choose Copy.</p>}
      <p>
        <button type="button" className="btn-ghost" onClick={onDone}>{doneLabel}</button>
      </p>
    </section>
  );
}
