import type { Me } from '../../../api/client';
import type { InviteUserBody, RoleAssignmentRequest, StaffBranch, StaffRole, StaffUser } from '../../../api/users';
import { normaliseUganda, phoneProblem } from '../lending/member-state';

// Who sees the Users screen, and the rules and words it uses (issue #319; FR-IAM-01, FR-IAM-02, FR-IAM-08). The API is the
// authority (it checks the permission, the branch scope, the plan and the last administrator); this decides what the menu and
// the page offer, from the /me permissions, and refuses an obviously wrong form before anything is sent.

export const USERS_READ = 'core.users.read';
export const USERS_MANAGE = 'core.users.manage';

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The page and its menu entries: everyone who may read the staff list. */
export const showUsers = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(USERS_READ);

/** Inviting, changing roles, sending a new link and deactivating: administrators only. Others see the list. */
export const canManageUsers = (me: Pick<Me, 'permissions'>): boolean => held(me).includes(USERS_MANAGE);

export const TENANT_ADMIN = 'tenant_admin';

/** What each fixed role is for, in the words of the people who pick it. The name is shown after it, with the key. */
const ROLE_WORDS: Record<string, { title: string; about: string }> = {
  tenant_admin: { title: 'Administrator', about: 'Runs the whole business account: staff, settings, branches and everything else. Covers every branch.' },
  branch_manager: { title: 'Branch manager', about: 'Runs one branch day to day: members, loans, stock and sales, and approves what needs a second person.' },
  loan_officer: { title: 'Loan officer', about: 'Registers members and handles loan applications and repayments.' },
  cashier: { title: 'Cashier', about: 'Takes and records money at the counter: repayments, deposits and withdrawals.' },
  accountant: { title: 'Accountant', about: 'Reads the books and reports, and keeps the accounts and periods.' },
  auditor: { title: 'Auditor', about: 'Can look at everything but change nothing.' },
  retail_sales: { title: 'Sales person', about: 'Sells in the shop and checks stock. Sees no costs or profit.' },
};

export interface RoleChoice {
  key: string;
  /** "Sales person (retail_sales)" */
  label: string;
  /** One plain sentence on what the role may do. */
  about: string;
}

/** A role as the pick-list shows it. A role this screen has no words for still appears, under the name the server gives it. */
export function roleChoice(role: Pick<StaffRole, 'key' | 'name'>): RoleChoice {
  const key = role.key ?? '';
  const words = ROLE_WORDS[key];
  return {
    key,
    label: `${words?.title ?? role.name ?? key} (${key})`,
    about: words?.about ?? 'Ask the platform operator what this role may do.',
  };
}

/** The pick-list: administrators first, then the others as the server lists them. */
export function roleChoices(roles: StaffRole[]): RoleChoice[] {
  const choices = roles.filter((r) => r.key).map(roleChoice);
  return [...choices.filter((c) => c.key === TENANT_ADMIN), ...choices.filter((c) => c.key !== TENANT_ADMIN)];
}

// --- The invite form -------------------------------------------------------------------------------

export interface InviteDraft {
  fullName: string;
  phone: string;
  email: string;
  roleKey: string;
  /** '' means every branch; ignored for the administrator. */
  branchId: string;
}

export const emptyInvite = (): InviteDraft => ({ fullName: '', phone: '', email: '', roleKey: '', branchId: '' });

export type InviteProblems = Partial<Record<'fullName' | 'contact' | 'phone' | 'email' | 'roleKey', string>>;

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function emailProblem(raw: string): string | null {
  const text = raw.trim();
  if (text === '') return null;
  return EMAIL.test(text) ? null : 'Check the email address: it looks like name@example.com.';
}

/** What is wrong with the form, in plain words; empty when it can be sent. */
export function inviteProblems(draft: InviteDraft): InviteProblems {
  const out: InviteProblems = {};
  if (draft.fullName.trim() === '') out.fullName = 'Type the person\'s full name.';
  const phone = draft.phone.trim() === '' ? null : phoneProblem(draft.phone);
  const email = emailProblem(draft.email);
  if (phone) out.phone = phone;
  if (email) out.email = email;
  if (draft.phone.trim() === '' && draft.email.trim() === '') out.contact = 'Give a phone number or an email address, so the person can be reached.';
  if (draft.roleKey === '') out.roleKey = 'Choose what this person will do.';
  return out;
}

export const hasProblems = (p: InviteProblems): boolean => Object.keys(p).length > 0;

/** The roles to send: the administrator covers every branch; any other role may be limited to one branch. */
export function assignmentFor(roleKey: string, branchId: string): RoleAssignmentRequest {
  return roleKey === TENANT_ADMIN || branchId === '' ? { role_key: roleKey } : { role_key: roleKey, branch_id: branchId };
}

/** The request body. A phone is written as the server would store it, so what is sent is what is checked here. */
export function inviteBody(draft: InviteDraft): InviteUserBody {
  const body: InviteUserBody = { full_name: draft.fullName.trim(), roles: [assignmentFor(draft.roleKey, draft.branchId)] };
  if (draft.phone.trim() !== '') body.phone = normaliseUganda(draft.phone) ?? draft.phone.trim();
  if (draft.email.trim() !== '') body.email = draft.email.trim();
  return body;
}

// --- Reading the list ------------------------------------------------------------------------------

export const STATUS_WORDS: Record<string, string> = { invited: 'Invited, not joined yet', active: 'Active', deactivated: 'Deactivated' };
export const statusWords = (status: string | undefined): string => STATUS_WORDS[status ?? ''] ?? (status ?? '');

export function branchLabel(branchId: string | undefined, branches: StaffBranch[]): string {
  if (!branchId) return 'every branch';
  const found = branches.find((b) => b.id === branchId);
  return found ? (found.name ?? found.code ?? 'one branch') : 'one branch';
}

/** "Sales person, Kampala branch" per role of the user. */
export function roleLines(user: StaffUser, branches: StaffBranch[], roles: StaffRole[]): string[] {
  return (user.roles ?? []).map((r) => {
    const found = roles.find((x) => x.key === r.role_key);
    const name = ROLE_WORDS[r.role_key ?? '']?.title ?? found?.name ?? r.role_name ?? r.role_key ?? '';
    return `${name}, ${branchLabel(r.branch_id, branches)}`;
  });
}

/** The contact line of a user: the phone first (phone first is how most staff are reached), then the email. */
export function contactLine(user: StaffUser): string {
  return [user.phone_e164, user.email].filter(Boolean).join(' · ');
}

// --- The one-time link -----------------------------------------------------------------------------

/** The expiry in the reader's own words, for example "Fri 23 Oct 2026, 14:30". */
export function expiryWords(iso: string | undefined): string {
  const d = iso ? new Date(iso) : null;
  return d && !Number.isNaN(d.getTime()) ? d.toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'soon';
}

/** The WhatsApp link with the message already typed. With a phone the chat opens on that person; without one, WhatsApp asks who. */
export function whatsappLink(opts: { phone?: string | null; name: string; business: string; url: string; expiresAt?: string }): string {
  const first = opts.name.trim().split(/\s+/)[0] || 'there';
  const text = `Hello ${first}, you have been invited to join ${opts.business}. Open this link to set up your sign-in: ${opts.url} . It works once and stops working on ${expiryWords(opts.expiresAt)}.`;
  const digits = (opts.phone ?? '').replace(/\D/g, '');
  return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`;
}
