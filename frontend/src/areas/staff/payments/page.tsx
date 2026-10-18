import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { type MerchantAccount, payments } from '../../../api/payments';
import { useStaff } from '../context';
import { STATUS_WORDS, canChangePaymentAccount, credentialProblem, failureWords, nextStep, showReceivePayments } from './state';

// "Receive payments" (extras slice A7, issue #233; ADR-035 amendment of 2026-10-18): money owed to the business lands only in
// the business's own Pesapal account. A business administrator enters the consumer key and secret (the secret is write-only: it
// is never shown again), tests the connection, and sees whether it works. Everyone who can read settings sees the status.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.');

function When({ iso }: { iso: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}</>;
}

export function AccountStatus({ account }: { account: MerchantAccount }) {
  const badge = account.status === 'verified' ? 'badge badge-success' : account.status === 'failed' ? 'badge badge-danger' : 'badge';
  return (
    <section className="rt-card stack" aria-labelledby="receive-status">
      <h2 id="receive-status">Your payment account</h2>
      <p>
        <span className={badge}>{STATUS_WORDS[account.status]}</span>
        {account.key_hint && <span className="hint"> Consumer key ending {account.key_hint}</span>}
      </p>
      <p role={account.status === 'failed' ? 'alert' : 'note'} className={account.status === 'failed' ? 'alert alert-danger' : 'hint'}>
        {nextStep(account)}
      </p>
      {account.last_checked_at && (
        <p className="hint">
          Last tested <When iso={account.last_checked_at} />.
        </p>
      )}
      {account.status === 'failed' && account.failure_reason && <p className="hint">{failureWords(account.failure_reason)}</p>}
    </section>
  );
}

export function CredentialsForm({ account, busy, error, onSave }: {
  account: MerchantAccount;
  busy: boolean;
  error: string | null;
  onSave: (key: string, secret: string) => void;
}) {
  const [key, setKey] = useState('');
  const [secret, setSecret] = useState('');
  const keyProblem = key === '' ? null : credentialProblem(key);
  const secretProblem = secret === '' ? null : credentialProblem(secret);
  const ready = key !== '' && secret !== '' && !credentialProblem(key) && !credentialProblem(secret);
  return (
    <section className="rt-card stack" aria-labelledby="receive-form">
      <h2 id="receive-form">{account.credentials_saved ? 'Change the key and secret' : 'Enter the key and secret'}</h2>
      <p className="hint">
        You find both in your own Pesapal account, under the API keys of your business. The secret cannot be shown again after you save it.
      </p>
      <form
        className="stack"
        aria-label="Consumer key and secret"
        autoComplete="off"
        onSubmit={(e) => {
          e.preventDefault();
          if (ready && !busy) {
            onSave(key.trim(), secret.trim());
            setSecret('');
          }
        }}
      >
        <label htmlFor="receive-key">Consumer key</label>
        <input id="receive-key" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" spellCheck={false} aria-invalid={keyProblem !== null} />
        {keyProblem && <p role="alert" className="field-error">{keyProblem}</p>}
        <label htmlFor="receive-secret">Consumer secret</label>
        <input
          id="receive-secret"
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          autoComplete="new-password"
          spellCheck={false}
          aria-invalid={secretProblem !== null}
        />
        {secretProblem && <p role="alert" className="field-error">{secretProblem}</p>}
        {error && <p role="alert" className="alert alert-danger">{error}</p>}
        <p>
          <button type="submit" className="rt-primary" disabled={!ready || busy}>{busy ? 'Saving' : 'Save the key and secret'}</button>
        </p>
      </form>
    </section>
  );
}

export function ReceivePaymentsPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [confirmRemove, setConfirmRemove] = useState(false);
  const query = useQuery({ queryKey: ['payments', 'account'], queryFn: payments.account, enabled: showReceivePayments(me) });
  const keep = (saved: MerchantAccount) => queryClient.setQueryData(['payments', 'account'], saved);
  const save = useMutation({ mutationFn: (v: { key: string; secret: string }) => payments.save(v.key, v.secret), onSuccess: keep });
  const test = useMutation({
    mutationFn: payments.test,
    onSuccess: keep,
    onError: () => void queryClient.invalidateQueries({ queryKey: ['payments', 'account'] }),
  });
  const remove = useMutation({
    mutationFn: payments.remove,
    onSuccess: (saved) => {
      keep(saved);
      setConfirmRemove(false);
    },
    onError: () => setConfirmRemove(false),
  });
  if (!showReceivePayments(me)) {
    return (
      <main className="rt ln">
        <h1>Receive payments</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const account = query.data;
  const canChange = canChangePaymentAccount(me);
  const busy = save.isPending || test.isPending || remove.isPending;
  return (
    <main className="rt ln stack">
      <h1>Receive payments</h1>
      <p>
        Members pay by mobile money or card into <strong>your own</strong> Pesapal account. This system never holds your money.
      </p>
      {query.error && <p role="alert" className="alert alert-danger">{messageOf(query.error)}</p>}
      {!account && !query.error && <p className="loading">Loading</p>}
      {account && <AccountStatus account={account} />}
      {account && canChange && account.credentials_saved && account.status !== 'verified' && (
        <section className="rt-card stack" aria-labelledby="receive-test">
          <h2 id="receive-test">Test the connection</h2>
          <p className="hint">We ask Pesapal for access with your key and secret and tell it where to report payments. Nothing is charged.</p>
          {test.error && <p role="alert" className="alert alert-danger">{messageOf(test.error)}</p>}
          <p>
            <button type="button" className="rt-primary" disabled={busy || !account.host_ready} onClick={() => test.mutate()}>
              {test.isPending ? 'Testing' : 'Test connection'}
            </button>
          </p>
        </section>
      )}
      {account && canChange && account.status === 'verified' && (
        <div className="stack">
          {test.error && <p role="alert" className="alert alert-danger">{messageOf(test.error)}</p>}
          <p>
            <button type="button" className="btn-ghost" disabled={busy || !account.host_ready} onClick={() => test.mutate()}>
              {test.isPending ? 'Testing' : 'Test again'}
            </button>
          </p>
        </div>
      )}
      {account && canChange && (
        <CredentialsForm key={account.key_hint ?? 'new'} account={account} busy={save.isPending} error={save.error ? messageOf(save.error) : null} onSave={(key, secret) => save.mutate({ key, secret })} />
      )}
      {account && canChange && account.credentials_saved && (
        <section className="rt-card stack" aria-labelledby="receive-remove">
          <h2 id="receive-remove">Remove the account</h2>
          {remove.error && <p role="alert" className="alert alert-danger">{messageOf(remove.error)}</p>}
          {!confirmRemove ? (
            <p>
              <button type="button" className="btn-ghost" disabled={busy} onClick={() => setConfirmRemove(true)}>Remove the saved key and secret</button>
            </p>
          ) : (
            <div className="stack" role="group" aria-label="Confirm removing the account">
              <p className="alert alert-warning">Collecting stops until you connect an account again. Payments already made are not affected.</p>
              <p className="cluster">
                <button type="button" className="rt-primary" disabled={busy} onClick={() => remove.mutate()}>Yes, remove it</button>
                <button type="button" className="btn-ghost" disabled={busy} onClick={() => setConfirmRemove(false)}>Keep it</button>
              </p>
            </div>
          )}
        </section>
      )}
      {account && !canChange && <p className="hint">Only a business administrator can change the key and secret.</p>}
    </main>
  );
}

export const Route = createLazyRoute('/staff/payments')({ component: ReceivePaymentsPage });
