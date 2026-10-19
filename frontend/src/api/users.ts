import { api, problemOf } from './client';
import type { components } from './schema';

// The staff users client (issue #319; chapter 7 section 7.11.4; FR-IAM-01, FR-IAM-02, FR-IAM-08). The generated types mark
// every field optional, as for the rest of the API; the screens read them through the small helpers in
// areas/staff/users/state.ts. The one-time invitation link is returned by the invite and the resend calls and is never put in a
// query cache: the screens hold it in their own state until the person leaves the page.

export type StaffUser = components['schemas']['User'];
export type StaffRole = components['schemas']['Role'];
export type RoleAssignment = components['schemas']['RoleAssignment'];
export type RoleAssignmentRequest = components['schemas']['RoleAssignmentRequest'];
export type InvitationLink = components['schemas']['InvitationLink'];
export type InvitedUser = components['schemas']['InvitedUser'];
export type InviteUserBody = components['schemas']['InviteUserRequest'];
export type StaffBranch = components['schemas']['Branch'];

export class UsersError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

/** The plan's refusal in plain words. The server names the limit as a number inside its sentence. */
export function planLimitWords(detail: string | undefined): string {
  const count = /at most (\d+)/.exec(detail ?? '')?.[1];
  const cap = count ? `Your plan allows at most ${count} staff users` : 'Your plan has reached its limit of staff users';
  return `${cap}, and that number is used up (people still waiting to join count). Deactivate someone you no longer need, or ask us to change your plan.`;
}

const MESSAGES: Record<string, string | undefined> = {
  last_tenant_admin: 'A business must always keep one active administrator. Make someone else an administrator first, then try again.',
  cannot_deactivate_self: 'You cannot deactivate your own account. Ask another administrator to do it.',
  duplicate_email: 'Someone on your staff already uses this email address.',
  duplicate_phone: 'Someone on your staff already uses this phone number.',
  invalid_phone: 'Use a Uganda mobile number such as 0712 345 678 or +256 712 345 678.',
  invalid_status_transition: 'This person has already joined, or was deactivated, so this is not possible now. Reload the page to see their status.',
  permission_denied: 'You are not allowed to do this. Ask an administrator.',
};

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  if (problem.code === 'plan_limit_reached') throw new UsersError(planLimitWords(problem.detail), status, problem.code);
  const first = (problem.errors ?? []).map((e) => e.message).find((m) => m);
  const message = (problem.code && MESSAGES[problem.code]) || first || problem.detail || fallback;
  throw new UsersError(message, status, problem.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) fail(result.error, result.response.status, fallback);
  return result.data;
}

export const users = {
  /** Every staff user, whatever their status (the list is small: a business has a handful of staff). */
  async list(): Promise<StaffUser[]> {
    const all: StaffUser[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < 20; page++) {
      const data = unwrap(await api.GET('/api/v1/users', { params: { query: { limit: 100, cursor } } }), 'Could not load your staff.');
      all.push(...(data.items ?? []));
      cursor = data.next_cursor ?? undefined;
      if (!cursor) break;
    }
    return all;
  },

  async roles(): Promise<StaffRole[]> {
    return unwrap(await api.GET('/api/v1/roles'), 'Could not load the roles.').items ?? [];
  },

  async branches(): Promise<StaffBranch[]> {
    return unwrap(await api.GET('/api/v1/branches'), 'Could not load the branches.').items ?? [];
  },

  async invite(body: InviteUserBody): Promise<InvitedUser> {
    return unwrap(await api.POST('/api/v1/users', { body }), 'Could not invite this person.');
  },

  async reissue(userId: string): Promise<InvitationLink> {
    return unwrap(await api.POST('/api/v1/users/{user_id}/invitation', { params: { path: { user_id: userId } } }), 'Could not make a new link.');
  },

  async setRoles(userId: string, roles: RoleAssignmentRequest[]): Promise<StaffUser> {
    return unwrap(await api.PUT('/api/v1/users/{user_id}/roles', { params: { path: { user_id: userId } }, body: roles }), 'Could not change the roles.');
  },

  async deactivate(userId: string): Promise<StaffUser> {
    return unwrap(await api.POST('/api/v1/users/{user_id}/deactivate', { params: { path: { user_id: userId } } }), 'Could not deactivate this person.');
  },
};
