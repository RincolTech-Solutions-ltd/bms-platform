import { describe, expect, it } from 'vitest';
import { planLimitWords } from '../../../api/users';
import {
  assignmentFor, canManageUsers, emptyInvite, hasProblems, inviteBody, inviteProblems, roleChoice, roleChoices, roleLines, showUsers, whatsappLink,
} from './state';

// The rules of the Users screen (issue #319), with fabricated people and numbers only.

const good = { ...emptyInvite(), fullName: 'Test Cashier 01', phone: '0700 000 001', roleKey: 'cashier' };

describe('who sees the screen', () => {
  it('shows the page to a reader and the actions to a manager only', () => {
    expect(showUsers({ permissions: ['core.users.read'] })).toBe(true);
    expect(showUsers({ permissions: ['core.settings.manage'] })).toBe(false);
    expect(canManageUsers({ permissions: ['core.users.read'] })).toBe(false);
    expect(canManageUsers({ permissions: ['core.users.read', 'core.users.manage'] })).toBe(true);
    expect(showUsers({})).toBe(false);
  });
});

describe('the invite form', () => {
  it('accepts a phone alone or an email alone, and wants one of them', () => {
    expect(hasProblems(inviteProblems(good))).toBe(false);
    expect(hasProblems(inviteProblems({ ...good, phone: '', email: 'test.cashier01@example.com' }))).toBe(false);
    expect(inviteProblems({ ...good, phone: '', email: '' }).contact).toMatch(/phone number or an email/);
  });

  it('asks for a name and a role', () => {
    const p = inviteProblems({ ...good, fullName: '  ', roleKey: '' });
    expect(p.fullName).toBeTruthy();
    expect(p.roleKey).toBeTruthy();
  });

  it('checks a Uganda phone the way the member forms do', () => {
    expect(inviteProblems({ ...good, phone: '0700000001' }).phone).toBeUndefined();
    expect(inviteProblems({ ...good, phone: '+256 700 000 001' }).phone).toBeUndefined();
    expect(inviteProblems({ ...good, phone: '700000001' }).phone).toMatch(/Start the number with 0/);
    expect(inviteProblems({ ...good, phone: '12345' }).phone).toMatch(/Uganda mobile number/);
    expect(inviteProblems({ ...good, phone: '+254700000001' }).phone).toBeTruthy();
  });

  it('checks an email', () => {
    expect(inviteProblems({ ...good, email: 'not-an-email' }).email).toBeTruthy();
    expect(inviteProblems({ ...good, email: 'test.cashier01@example.com' }).email).toBeUndefined();
  });

  it('sends the phone as the server stores it, and leaves out what was not typed', () => {
    expect(inviteBody(good)).toEqual({ full_name: 'Test Cashier 01', phone: '+256700000001', roles: [{ role_key: 'cashier' }] });
    expect(inviteBody({ ...good, phone: '', email: ' test.cashier01@example.com ', branchId: 'b1' })).toEqual({
      full_name: 'Test Cashier 01',
      email: 'test.cashier01@example.com',
      roles: [{ role_key: 'cashier', branch_id: 'b1' }],
    });
  });

  it('never sends a branch with the administrator role, which covers every branch', () => {
    expect(assignmentFor('tenant_admin', 'b1')).toEqual({ role_key: 'tenant_admin' });
    expect(assignmentFor('loan_officer', '')).toEqual({ role_key: 'loan_officer' });
    expect(assignmentFor('loan_officer', 'b1')).toEqual({ role_key: 'loan_officer', branch_id: 'b1' });
  });
});

describe('roles in plain words', () => {
  it('names a role as "Sales person (retail_sales)" with what it is for', () => {
    const c = roleChoice({ key: 'retail_sales', name: 'Sales' });
    expect(c.label).toBe('Sales person (retail_sales)');
    expect(c.about).toMatch(/Sells in the shop/);
  });

  it('keeps a role it has no words for, under the name the server gives it, administrator first', () => {
    const list = roleChoices([{ key: 'cashier', name: 'Cashier' }, { key: 'new_role', name: 'Stock clerk' }, { key: 'tenant_admin', name: 'Tenant admin' }]);
    expect(list.map((c) => c.key)).toEqual(['tenant_admin', 'cashier', 'new_role']);
    expect(list[2]?.label).toBe('Stock clerk (new_role)');
  });

  it('writes a user\'s roles with their branch', () => {
    const user = { roles: [{ role_key: 'cashier', branch_id: 'b1' }, { role_key: 'auditor' }] };
    expect(roleLines(user, [{ id: 'b1', name: 'Test Branch One' }], [])).toEqual(['Cashier, Test Branch One', 'Auditor, every branch']);
  });
});

describe('the plan limit', () => {
  it('turns the server sentence into plain words with the number', () => {
    const words = planLimitWords('The plan allows at most 5 (max_staff_users); the limit has been reached.');
    expect(words).toMatch(/at most 5 staff users/);
    expect(words).not.toMatch(/max_staff_users/);
    expect(planLimitWords(undefined)).toMatch(/limit of staff users/);
  });
});

describe('the WhatsApp message', () => {
  it('opens the chat of the invited phone with the link and the expiry in the text', () => {
    const href = whatsappLink({ phone: '+256700000001', name: 'Test Cashier 01', business: 'Test Trading Co', url: 'https://test.example/accept-invitation#token=abc', expiresAt: '2026-10-22T10:00:00Z' });
    expect(href.startsWith('https://wa.me/256700000001?text=')).toBe(true);
    const text = decodeURIComponent(href.split('?text=')[1] ?? '');
    expect(text).toContain('Hello Test,');
    expect(text).toContain('Test Trading Co');
    expect(text).toContain('https://test.example/accept-invitation#token=abc');
    expect(text).toMatch(/stops working on/);
  });

  it('lets WhatsApp ask who to send to when there is no phone', () => {
    expect(whatsappLink({ phone: null, name: 'Test', business: 'B', url: 'https://x.example/l' }).startsWith('https://wa.me/?text=')).toBe(true);
  });
});
