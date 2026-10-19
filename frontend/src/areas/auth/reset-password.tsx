import { Link, createLazyRoute, useNavigate } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { api, type MfaEnrolment, problemOf, type SignInResponse } from '../../api/client';
import { invitationToken, normaliseSecondFactor, plainProblem } from '../../auth/codes';
import { setAccessToken } from '../../auth/session';
import { illustrations } from '../../components/illustrations';
import { StatusPanel } from '../../components/states';
import { ErrorLine, PasswordFields, RecoveryCodes, StepHeader, TwoStepSetup, passwordReady } from './steps';

// Choosing a new password with the link from "Forgot password?" or from an administrator (FR-IAM-15, ADR-042). The one-time token is
// in the link's fragment, so it never reaches a server log. The same password rules as the first run apply. Saving signs every other
// device out. Two-step sign-in is never skipped: a user who has it types the code next, and a role that requires it sets it up.

export type Step =
  | { kind: 'password' }
  | { kind: 'challenge'; mfaToken: string }
  | { kind: 'enrol'; enrolment: MfaEnrolment; mfaToken: string }
  | { kind: 'codes'; codes: string[] }
  | { kind: 'sign-in' };

/** `initial` lets a test open any step; the page always starts at the password. */
export function ResetPassword({ token, initial = { kind: 'password' } }: { token: string | null; initial?: Step }) {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>(initial);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<void>, fallback: string) {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (error) {
      setMessage(plainProblem(problemOf(error), fallback));
    } finally {
      setBusy(false);
    }
  }

  const home = () => void navigate({ to: '/staff' });

  async function afterPassword(body: SignInResponse) {
    // The token has done its job; drop it from the address bar and the history.
    window.history.replaceState(null, '', window.location.pathname);
    if (body.status === 'signed_in' && body.access_token) {
      setAccessToken(body.access_token);
      home();
    } else if (body.status === 'mfa_required' && body.mfa_token) {
      setStep({ kind: 'challenge', mfaToken: body.mfa_token });
    } else if (body.status === 'mfa_enrolment_required' && body.mfa_token) {
      const mfaToken = body.mfa_token;
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/enrol', { body: { mfa_token: mfaToken } });
      if (error || !data) throw error;
      setStep({ kind: 'enrol', enrolment: data, mfaToken });
    } else {
      setStep({ kind: 'sign-in' });
    }
  }

  function submitPassword(e: FormEvent) {
    e.preventDefault();
    if (!token || !passwordReady(password, confirm)) {
      setMessage('Check the password rules below the boxes.');
      return;
    }
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/password-reset/complete', { body: { token, password } });
      if (error || !data) throw error;
      setPassword('');
      setConfirm('');
      await afterPassword(data);
    }, 'Your password could not be saved. Try again.');
  }

  function submitChallenge(e: FormEvent, mfaToken: string) {
    e.preventDefault();
    const factor = normaliseSecondFactor(code);
    if (factor.kind === 'invalid') {
      setMessage('Enter the 6 digit code from your app, or one recovery code.');
      return;
    }
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/verify', { body: { mfa_token: mfaToken, code: factor.code } });
      if (error || !data) throw error;
      setCode('');
      if (data.access_token) setAccessToken(data.access_token);
      home();
    }, 'That code did not work. Try again.');
  }

  function confirmCode(e: FormEvent, mfaToken: string) {
    e.preventDefault();
    void run(async () => {
      const { data, error } = await api.POST('/api/v1/auth/staff/mfa/confirm', { body: { mfa_token: mfaToken, code } });
      if (error || !data) throw error;
      setCode('');
      if (data.access_token) setAccessToken(data.access_token);
      setStep({ kind: 'codes', codes: data.recovery_codes ?? [] });
    }, 'That code did not work. Try again.');
  }

  if (!token && step.kind === 'password') {
    return (
      <main className="card auth-card">
        <StepHeader title="Choose a new password" />
        <StatusPanel
          tone="error"
          title="This reset link is not complete."
          action={
            <Link to="/forgot-password" className="btn btn-primary">
              Ask for a new link
            </Link>
          }
        >
          Open the link from your email again, or ask for a new one. An administrator can also give you a link.
        </StatusPanel>
      </main>
    );
  }

  return (
    <main className="card auth-card">
      {step.kind === 'password' && (
        <>
          <span className="auth-art">{illustrations.secure}</span>
          <StepHeader title="Choose a new password" lead="You will use it with your email or phone to sign in. Every device you were signed in on will be signed out." />
          <ErrorLine message={message} />
          <form onSubmit={submitPassword} className="form-stack" noValidate>
            <PasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} />
            <button className="btn-primary btn-lg" disabled={busy}>
              {busy ? 'Saving' : 'Save and sign in'}
            </button>
          </form>
        </>
      )}

      {step.kind === 'challenge' && (
        <>
          <StepHeader title="Enter your code" lead="Your password is changed. Open your authenticator app and type the 6 digit code it shows." />
          <ErrorLine message={message} />
          <form onSubmit={(e) => submitChallenge(e, step.mfaToken)} className="form-stack">
            <div>
              <label htmlFor="reset-code">Code</label>
              <input id="reset-code" className="input-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
              <span className="field-hint">Lost your phone? Type one of your recovery codes instead.</span>
            </div>
            <button className="btn-primary btn-lg" disabled={busy}>
              {busy ? 'Checking' : 'Continue'}
            </button>
          </form>
        </>
      )}

      {step.kind === 'enrol' && (
        <>
          <StepHeader step={1} of={2} title="Set up two-step sign-in" lead="Your role needs a code from your phone as well as your password." />
          <ErrorLine message={message} />
          <TwoStepSetup enrolment={step.enrolment} code={code} onCode={setCode} busy={busy} onSubmit={(e) => confirmCode(e, step.mfaToken)} />
        </>
      )}

      {step.kind === 'codes' && (
        <>
          <StepHeader step={2} of={2} title="Save your recovery codes" />
          <RecoveryCodes codes={step.codes} onDone={home} />
        </>
      )}

      {step.kind === 'sign-in' && (
        <>
          <StepHeader title="Your password is changed" />
          <StatusPanel tone="success" title="Your password is changed.">
            Sign in with your email or phone and the new password. If your account is locked after too many tries, wait 15 minutes first.
          </StatusPanel>
          <Link to="/sign-in" className="btn btn-primary btn-lg btn-block">
            Sign in
          </Link>
        </>
      )}
    </main>
  );
}

function ResetPasswordPage() {
  const [token] = useState(() => invitationToken(window.location.hash));
  return <ResetPassword token={token} />;
}

export const Route = createLazyRoute('/reset-password')({ component: ResetPasswordPage });
