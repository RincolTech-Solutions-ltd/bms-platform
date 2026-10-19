import { api, problemOf } from './client';

// The forgotten staff password (FR-IAM-15, ADR-042; chapter 7 section 7.11.2 and 7.11.4). Two calls:
//  - requestPasswordReset: the sign-in page's "Forgot password?" form. It answers the same for every login, so the page can too.
//  - resetUserSignIn: the Users screen's admin action. It returns the one-time link, shown once to the admin to hand to the user.

export interface SignInResetLink {
  /** The link the user opens to choose a new password; contains the token in its fragment */
  url: string;
  /** When the link stops working (one hour after it was issued), an ISO 8601 time */
  expiresAt: string;
}

export class SignInResetError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  permission_denied: 'Only a tenant administrator can reset someone’s sign-in.',
  not_found: 'This user could not be found. Reload the list and try again.',
  rate_limited: 'Too many tries from here. Wait a few minutes and try again.',
};

function failure(error: unknown, status: number, fallback: string): SignInResetError {
  const problem = problemOf(error);
  const message = (problem.code && MESSAGES[problem.code]) || problem.detail || fallback;
  return new SignInResetError(message, status, problem.code);
}

/** The generated client types an answer with no error body as never; read it through the shape every answer has. */
function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    throw failure(result.error, result.response.status, fallback);
  }
  return result.data;
}

/**
 * Asks for a reset link for an email or phone. Resolves with the words to show, whether or not an account matched: the server
 * never says, and neither may the page.
 */
export async function requestPasswordReset(login: string): Promise<string> {
  const data = unwrap(await api.POST('/api/v1/auth/staff/password/forgot', { body: { login } }), 'The request could not be sent. Try again.');
  return data.message ?? 'If an account matches, a reset link has been sent.';
}

/**
 * Issues a one-time link for an active user (POST /api/v1/users/{id}/reset-sign-in, permission core.users.manage). The link is
 * returned once and never emailed: show it to the admin to hand over, and do not keep it. Issuing another replaces it. A user who
 * has not accepted the invitation yet (409) keeps the invitation link, and a deactivated user (409) cannot reset; the error's
 * message says which, in plain words.
 */
export async function resetUserSignIn(userId: string): Promise<SignInResetLink> {
  const data = unwrap(
    await api.POST('/api/v1/users/{user_id}/reset-sign-in', { params: { path: { user_id: userId } } }),
    'The link could not be made. Try again.',
  );
  if (!data.url || !data.expires_at) throw new SignInResetError('The link could not be made. Try again.', 200);
  return { url: data.url, expiresAt: data.expires_at };
}
