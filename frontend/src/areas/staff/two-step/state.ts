import type { Me, TenantSettings } from '../../../api/client';
import type { Choice, Policy } from '../../../api/two-step';

// The rules of the Two-step sign-in screens (issue #348; ADR-046), kept out of the components so they can be tested: who may change the
// policy, what the stored settings mean as a choice, the plain-words review before saving, the date shown to people, the reminder for a
// person the policy covers, and the recommendation for administrators while the policy is off.

const MANAGE = 'core.settings.manage';

/** Only the people who may change the settings change the policy (no new permission, ADR-046 decision 2). */
export const canManageTwoStep = (me: Pick<Me, 'permissions'>): boolean => (me.permissions ?? []).includes(MANAGE);

/** The grace periods offered; a stored value outside this list is added by {@link graceChoices} so it still reads. */
export const GRACE_CHOICES: { days: number; label: string }[] = [
  { days: 0, label: 'No wait: from the next sign-in' },
  { days: 7, label: '7 days' },
  { days: 14, label: '14 days' },
  { days: 30, label: '30 days' },
];

/** What the screen suggests for a business turning it on for the first time. */
export const DEFAULT_GRACE = 14;

export const graceChoices = (current: number): { days: number; label: string }[] =>
  GRACE_CHOICES.some((g) => g.days === current) ? GRACE_CHOICES : [...GRACE_CHOICES, { days: current, label: `${current} days` }].sort((a, b) => a.days - b.days);

type Stored = Pick<TenantSettings, 'require_mfa_all_staff' | 'require_mfa_roles' | 'mfa_grace_days'>;

/** The policy the stored settings amount to: everyone wins over roles, and neither means nobody is required. */
export function policyOf(settings: Stored): Policy {
  if (settings.require_mfa_all_staff) return 'everyone';
  return (settings.require_mfa_roles ?? []).length > 0 ? 'roles' : 'off';
}

/** The stored settings as the screen's starting choice. */
export function choiceOf(settings: Stored): Choice {
  return { policy: policyOf(settings), roles: [...(settings.require_mfa_roles ?? [])].sort(), graceDays: settings.mfa_grace_days ?? DEFAULT_GRACE };
}

/**
 * Whether saving this would change anything. The grace period counts only while something is required, and it counts because
 * sending it moves the deadline to now plus these days.
 */
export function differs(a: Choice, b: Choice): boolean {
  if (a.policy !== b.policy) return true;
  if (a.policy === 'off') return false;
  if (a.graceDays !== b.graceDays) return true;
  return a.policy === 'roles' && [...a.roles].sort().join('|') !== [...b.roles].sort().join('|');
}

/** A save needs the roles it names: "chosen roles" with none chosen is not a choice yet. */
export const ready = (choice: Choice): boolean => choice.policy !== 'roles' || choice.roles.length > 0;

/** "3 Nov 2026" in the business's time zone, or null for something that is not a date. */
export function dateWords(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return null;
  return when.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Africa/Kampala' });
}

/** The day the grace period of this choice would end if it were saved at `now`. */
export const deadlineOf = (graceDays: number, now: Date): string => new Date(now.getTime() + graceDays * 86_400_000).toISOString();

const listWords = (names: string[]): string => (names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`);

/**
 * The review shown before the policy is saved, one plain sentence per line. It says who is asked, from when, that nobody is locked out
 * before the date, that the person saving is asked too when they are covered, and what happens when someone loses their phone.
 */
export function reviewLines(choice: Choice, roleNames: Record<string, string>, me: Pick<Me, 'roles'>, now: Date): string[] {
  if (choice.policy === 'off') {
    return ['Nobody will be required to use it. People who have already set it up keep using it, and anyone can still turn it on for themselves.'];
  }
  const lines: string[] = [];
  if (choice.policy === 'everyone') {
    lines.push('Everyone who signs in to the staff area will be asked to use it.');
  } else {
    const names = choice.roles.map((key) => roleNames[key] ?? key);
    lines.push(`People with the ${names.length === 1 ? 'role' : 'roles'} ${listWords(names)} will be asked to use it.`);
  }
  if (choice.graceDays === 0) {
    lines.push('There is no wait: anyone who has not set it up is asked the next time they sign in, and cannot use the app until they have.');
  } else {
    const date = dateWords(deadlineOf(choice.graceDays, now));
    lines.push(
      `Nobody is locked out before ${date}. Until then people who have not set it up can keep signing in and see a reminder. After that, they are asked at their next sign-in and cannot use the app until they have set it up.`,
    );
  }
  const mine = (me.roles ?? []).map((r) => r.role_key);
  if (choice.policy === 'everyone' || choice.roles.some((key) => mine.includes(key))) {
    lines.push('You are covered too, so you will be asked as well.');
  }
  lines.push(
    'If someone loses their phone, an administrator can reset their two-step sign-in. If you have only one administrator, we have to help them, so ask them to keep their recovery codes safe.',
  );
  return lines;
}

/** The reminder for a person the policy covers who has not set it up yet, or null. */
export function reminderWords(me: Pick<Me, 'mfa_due_by' | 'mfa_enabled'>): string | null {
  const date = me.mfa_enabled ? null : dateWords(me.mfa_due_by);
  return date ? `Your business asks you to set up two-step sign-in by ${date}. After that day you cannot sign in without it.` : null;
}

/** The recommendation is for people who may change the policy, while it is off. */
export const recommends = (me: Pick<Me, 'permissions' | 'two_step_policy'>): boolean => canManageTwoStep(me) && me.two_step_policy === 'off';

// "Not now" puts the recommendation away for 14 days. It is a convenience of this browser only (storage can be empty, blocked or
// throw), so the page works without it and the recommendation simply comes back.
const HIDE_DAYS = 14;
const keyOf = (userId: string | undefined) => `bms.twoStep.recommendation.${userId ?? 'me'}`;

type Box = Pick<Storage, 'getItem' | 'setItem'>;

function box(): Box | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function recommendationHidden(userId: string | undefined, now: number, storage: Box | null = box()): boolean {
  try {
    const until = Number(storage?.getItem(keyOf(userId)));
    return Number.isFinite(until) && until > now;
  } catch {
    return false;
  }
}

export function hideRecommendation(userId: string | undefined, now: number, storage: Box | null = box()): void {
  try {
    storage?.setItem(keyOf(userId), String(now + HIDE_DAYS * 86_400_000));
  } catch {
    // Nothing to do: the recommendation will show again.
  }
}
