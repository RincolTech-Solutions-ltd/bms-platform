import { describe, expect, it } from 'vitest';
import { bodyOf } from '../../../api/two-step';
import {
  GRACE_CHOICES,
  canManageTwoStep,
  choiceOf,
  dateWords,
  deadlineOf,
  differs,
  graceChoices,
  hideRecommendation,
  policyOf,
  ready,
  recommendationHidden,
  recommends,
  reminderWords,
  reviewLines,
} from './state';

// The rules of the Two-step sign-in screens (issue #348; ADR-046). All people and roles are invented.

const NOW = new Date('2026-10-20T09:00:00Z');
const NAMES = { cashier: 'Cashier', accountant: 'Accountant', tenant_admin: 'Tenant admin' };
const ADMIN_ME = { roles: [{ role_key: 'tenant_admin' }] };

describe('who may change it', () => {
  it('is whoever may change the settings, and no one else', () => {
    expect(canManageTwoStep({ permissions: ['core.settings.manage'] })).toBe(true);
    expect(canManageTwoStep({ permissions: ['core.settings.read'] })).toBe(false);
    expect(canManageTwoStep({})).toBe(false);
  });
});

describe('the stored settings as a choice', () => {
  it('reads nothing set as optional, with the suggested grace period', () => {
    expect(policyOf({})).toBe('off');
    expect(choiceOf({})).toEqual({ policy: 'off', roles: [], graceDays: 14 });
  });

  it('lets everyone win over roles, and roles win over nothing', () => {
    expect(policyOf({ require_mfa_all_staff: true, require_mfa_roles: ['cashier'] })).toBe('everyone');
    expect(policyOf({ require_mfa_roles: ['cashier'] })).toBe('roles');
    expect(policyOf({ require_mfa_all_staff: false, require_mfa_roles: [] })).toBe('off');
    expect(choiceOf({ require_mfa_roles: ['cashier', 'accountant'], mfa_grace_days: 7 })).toEqual({ policy: 'roles', roles: ['accountant', 'cashier'], graceDays: 7 });
  });

  it('offers a stored grace period that is not on the list, in its place', () => {
    expect(graceChoices(14)).toBe(GRACE_CHOICES);
    expect(graceChoices(5).map((g) => g.days)).toEqual([0, 5, 7, 14, 30]);
  });
});

describe('whether saving would change anything', () => {
  const off = { policy: 'off' as const, roles: [], graceDays: 14 };
  it('ignores the grace period while nothing is required', () => {
    expect(differs(off, { ...off, graceDays: 30 })).toBe(false);
    expect(differs(off, { ...off, policy: 'everyone' })).toBe(true);
  });

  it('counts a new grace period or other roles once something is required', () => {
    const roles = { policy: 'roles' as const, roles: ['cashier'], graceDays: 14 };
    expect(differs(roles, { ...roles })).toBe(false);
    expect(differs(roles, { ...roles, graceDays: 7 })).toBe(true);
    expect(differs(roles, { ...roles, roles: ['cashier', 'accountant'] })).toBe(true);
    expect(differs(roles, { ...roles, roles: ['cashier'] })).toBe(false);
  });

  it('needs a role when roles are chosen', () => {
    expect(ready({ policy: 'roles', roles: [], graceDays: 14 })).toBe(false);
    expect(ready({ policy: 'roles', roles: ['cashier'], graceDays: 14 })).toBe(true);
    expect(ready({ policy: 'everyone', roles: [], graceDays: 0 })).toBe(true);
  });
});

describe('what is sent', () => {
  it('sends the grace period only while something is required, and empties the roles unless roles are chosen', () => {
    expect(bodyOf({ policy: 'off', roles: ['cashier'], graceDays: 14 })).toEqual({ require_mfa_all_staff: false, require_mfa_roles: [] });
    expect(bodyOf({ policy: 'everyone', roles: ['cashier'], graceDays: 14 })).toEqual({ require_mfa_all_staff: true, require_mfa_roles: [], mfa_grace_days: 14 });
    expect(bodyOf({ policy: 'roles', roles: ['cashier'], graceDays: 0 })).toEqual({ require_mfa_all_staff: false, require_mfa_roles: ['cashier'], mfa_grace_days: 0 });
  });
});

describe('dates', () => {
  it('shows a day in the business time zone and nothing for what is not a date', () => {
    expect(dateWords('2026-11-03T09:00:00Z')).toBe('3 Nov 2026');
    expect(dateWords('2026-11-03T22:30:00Z')).toBe('4 Nov 2026');
    expect(dateWords(null)).toBeNull();
    expect(dateWords('not a date')).toBeNull();
  });

  it('puts the end of the grace period that many days on', () => {
    expect(deadlineOf(14, NOW)).toBe('2026-11-03T09:00:00.000Z');
    expect(deadlineOf(0, NOW)).toBe('2026-10-20T09:00:00.000Z');
  });
});

describe('the review before saving', () => {
  it('says who, from when, that nobody is locked out before then, and what happens to a lost phone', () => {
    const lines = reviewLines({ policy: 'roles', roles: ['cashier', 'accountant'], graceDays: 14 }, NAMES, ADMIN_ME, NOW);
    expect(lines[0]).toBe('People with the roles Cashier and Accountant will be asked to use it.');
    expect(lines[1]).toContain('Nobody is locked out before 3 Nov 2026.');
    expect(lines.join(' ')).not.toContain('You are covered too');
    expect(lines[lines.length - 1]).toContain('If someone loses their phone');
  });

  it('names one role in the singular and tells the administrator when they are covered too', () => {
    const lines = reviewLines({ policy: 'roles', roles: ['tenant_admin'], graceDays: 7 }, NAMES, ADMIN_ME, NOW);
    expect(lines[0]).toBe('People with the role Tenant admin will be asked to use it.');
    expect(lines).toContain('You are covered too, so you will be asked as well.');
    expect(reviewLines({ policy: 'everyone', roles: [], graceDays: 7 }, NAMES, { roles: [] }, NOW)).toContain('You are covered too, so you will be asked as well.');
  });

  it('says there is no wait when the grace period is none, and that it stays optional when it is off', () => {
    const now = reviewLines({ policy: 'everyone', roles: [], graceDays: 0 }, NAMES, ADMIN_ME, NOW);
    expect(now[0]).toBe('Everyone who signs in to the staff area will be asked to use it.');
    expect(now[1]).toContain('There is no wait');
    expect(now.join(' ')).not.toContain('locked out before');
    expect(reviewLines({ policy: 'off', roles: [], graceDays: 14 }, NAMES, ADMIN_ME, NOW)).toEqual([
      'Nobody will be required to use it. People who have already set it up keep using it, and anyone can still turn it on for themselves.',
    ]);
  });

  it('falls back to the role key for a role it has no name for', () => {
    expect(reviewLines({ policy: 'roles', roles: ['mystery'], graceDays: 7 }, NAMES, ADMIN_ME, NOW)[0]).toContain('role mystery');
  });
});

describe('the reminder for a covered person', () => {
  it('names the day, only for someone without a second factor who has a deadline', () => {
    expect(reminderWords({ mfa_due_by: '2026-11-03T09:00:00Z', mfa_enabled: false })).toBe(
      'Your business asks you to set up two-step sign-in by 3 Nov 2026. After that day you cannot sign in without it.',
    );
    expect(reminderWords({ mfa_due_by: '2026-11-03T09:00:00Z', mfa_enabled: true })).toBeNull();
    expect(reminderWords({ mfa_enabled: false })).toBeNull();
  });
});

describe('the recommendation to administrators', () => {
  it('shows only to a settings manager while the policy is off', () => {
    expect(recommends({ permissions: ['core.settings.manage'], two_step_policy: 'off' })).toBe(true);
    expect(recommends({ permissions: ['core.settings.manage'], two_step_policy: 'roles' })).toBe(false);
    expect(recommends({ permissions: ['core.settings.manage'], two_step_policy: 'everyone' })).toBe(false);
    expect(recommends({ permissions: [], two_step_policy: 'off' })).toBe(false);
    expect(recommends({ permissions: ['core.settings.manage'] })).toBe(false);
  });

  it('is put away for 14 days per person and comes back by itself', () => {
    const store = new Map<string, string>();
    const box = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) };
    const t0 = 1_000_000;
    expect(recommendationHidden('u1', t0, box)).toBe(false);
    hideRecommendation('u1', t0, box);
    expect(recommendationHidden('u1', t0 + 13 * 86_400_000, box)).toBe(true);
    expect(recommendationHidden('u1', t0 + 15 * 86_400_000, box)).toBe(false);
    expect(recommendationHidden('u2', t0 + 1, box)).toBe(false);
  });

  it('works without storage: it simply shows', () => {
    const broken = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(recommendationHidden('u1', 1, broken)).toBe(false);
    expect(() => hideRecommendation('u1', 1, broken)).not.toThrow();
    expect(recommendationHidden('u1', 1, null)).toBe(false);
    expect(() => hideRecommendation('u1', 1, null)).not.toThrow();
  });
});
