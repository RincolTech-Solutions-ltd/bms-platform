import { api, fetchSettings, problemOf, type MfaEnrolment, type TenantSettings } from './client';

// The two-step sign-in client (issue #348; ADR-046; chapter 7 sections 7.11.2 and 7.11.4). The tenant's policy is three keys of the
// settings resource, changed with PATCH /settings and the settings version in If-Match; the signed-in set-up of a person's own second
// factor uses the same enrol and confirm routes as the first run, with the session instead of a sign-in token.

export type Policy = 'off' | 'roles' | 'everyone';

/** What the administrator chose on the screen. */
export type Choice = { policy: Policy; roles: string[]; graceDays: number };

export class TwoStepError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  version_conflict: 'Someone else changed the settings while you were looking. Reload the page and choose again.',
  permission_denied: 'You are not allowed to change this. Ask an administrator.',
  validation_failed: 'One of the roles you picked is not available. Reload the page and choose again.',
  invalid_mfa_code: 'That code is not right. Codes change every 30 seconds; try the next one.',
  mfa_already_enrolled: 'Two-step sign-in is already on for your account.',
};

const NETWORK_WORDS = 'The answer did not reach your phone, so we cannot tell if it worked. Reload the page to see how it is now.';

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  throw new TwoStepError((problem.code && MESSAGES[problem.code]) || problem.detail || fallback, status, problem.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) fail(result.error, result.response.status, fallback);
  return result.data;
}

async function sent<T>(call: Promise<T>): Promise<T> {
  try {
    return await call;
  } catch (e) {
    if (e instanceof TwoStepError) throw e;
    throw new TwoStepError(NETWORK_WORDS, 0, 'network');
  }
}

/** The settings body for a choice. The grace period is sent only while something is required: it moves the deadline. */
export function bodyOf(choice: Choice) {
  return {
    require_mfa_all_staff: choice.policy === 'everyone',
    require_mfa_roles: choice.policy === 'roles' ? choice.roles : [],
    ...(choice.policy === 'off' ? {} : { mfa_grace_days: choice.graceDays }),
  };
}

export const twoStep = {
  settings: (): Promise<TenantSettings> => fetchSettings(),

  async save(version: number, choice: Choice): Promise<TenantSettings> {
    return unwrap(
      await sent(api.PATCH('/api/v1/settings', { params: { header: { 'If-Match': `"${version}"` } }, body: bodyOf(choice) })),
      'Could not save your choice.',
    );
  },

  /** Starts the signed-in user's own set-up: a secret and a QR address, shown once. */
  async start(): Promise<MfaEnrolment> {
    return unwrap(await sent(api.POST('/api/v1/auth/staff/mfa/enrol', {})), 'Two-step sign-in could not start. Try again.');
  },

  /** Turns it on with the first code and returns the ten recovery codes, shown once. */
  async confirm(code: string): Promise<string[]> {
    const data = unwrap(await sent(api.POST('/api/v1/auth/staff/mfa/confirm', { body: { code } })), 'That code did not work. Try again.');
    return data.recovery_codes ?? [];
  },
};
