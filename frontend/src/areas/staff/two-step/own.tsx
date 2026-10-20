import { useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import type { MfaEnrolment } from '../../../api/client';
import { twoStep } from '../../../api/two-step';
import { ErrorLine, RecoveryCodes, TwoStepSetup } from '../../auth/steps';
import { useStaff } from '../context';
import { dateWords } from './state';

// Turn on your own two-step sign-in from inside the app (issue #348; ADR-046 decision 7). Until now only the first run of an invited
// user and a forced enrolment could do it. Same three moves as the first run: start (a QR code and a key), type the first code, save the
// ten recovery codes once. The person who is covered by the business's policy is told the date; anyone else is just offered it.

type Step = { kind: 'start' } | { kind: 'code'; enrolment: MfaEnrolment } | { kind: 'codes'; codes: string[] };

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

export function OwnTwoStep() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [step, setStep] = useState<Step>({ kind: 'start' });
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const due = me.mfa_enabled ? null : dateWords(me.mfa_due_by);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      setMessage(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  const start = () =>
    run(async () => {
      setStep({ kind: 'code', enrolment: await twoStep.start() });
    });

  function confirm(e: FormEvent) {
    e.preventDefault();
    void run(async () => {
      const codes = await twoStep.confirm(code);
      setCode('');
      setStep({ kind: 'codes', codes });
      await queryClient.invalidateQueries({ queryKey: ['me'] });
    });
  }

  return (
    <main className="rt ln stack" data-tour="twostep-own">
      <h1>Your two-step sign-in</h1>
      {step.kind === 'start' && me.mfa_enabled && (
        <p role="status" className="alert alert-success">
          Two-step sign-in is on for your account. You give a code from your phone each time you sign in.
        </p>
      )}
      {step.kind === 'start' && !me.mfa_enabled && (
        <>
          {due && (
            <p role="status" className="alert alert-warning">
              Your business asks you to set it up by {due}. After that day you cannot sign in without it.
            </p>
          )}
          <p>
            Two-step sign-in asks for a code from your phone as well as your password. Then nobody can sign in as you, even if they learn your
            password. It takes about two minutes.
          </p>
          <ErrorLine message={message} />
          <p className="cluster">
            <button type="button" className="btn-primary btn-lg" disabled={busy} onClick={() => void start()}>
              {busy ? 'Starting' : 'Turn on two-step sign-in'}
            </button>
            <Link to="/staff" className="btn-ghost">
              Not now
            </Link>
          </p>
        </>
      )}
      {step.kind === 'code' && (
        <>
          <ErrorLine message={message} />
          <TwoStepSetup enrolment={step.enrolment} code={code} onCode={setCode} busy={busy} onSubmit={confirm} />
        </>
      )}
      {step.kind === 'codes' && (
        <>
          <p role="status" className="alert alert-success">
            Two-step sign-in is on.
          </p>
          <RecoveryCodes codes={step.codes} doneLabel="Done" onDone={() => setStep({ kind: 'start' })} />
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/two-step')({ component: OwnTwoStep });
