import { describe, expect, it } from 'vitest';
import { normalisePhone, receiptCaption, whatsAppLink } from './receipt-share-state';

// Issue #439: the phone rules ported from the company's other product, and the caption. Every number is fabricated.

const ok = (typed: string) => {
  const r = normalisePhone(typed);
  if (!r.ok) throw new Error(`refused ${typed}: ${r.problem}`);
  return r.digits;
};
const refused = (typed: string) => {
  const r = normalisePhone(typed);
  if (r.ok) throw new Error(`accepted ${typed} as ${r.digits}`);
  return r.problem;
};

describe('normalisePhone', () => {
  it('strips spaces, dashes, brackets and dots', () => {
    expect(ok('+256 700-000.011')).toBe('256700000011');
    expect(ok('(0700) 000 011')).toBe('256700000011');
  });

  it('reads 00 as the international prefix', () => {
    expect(ok('00256700000012')).toBe('256700000012');
    expect(ok('0044 7700 900123')).toBe('447700900123');
  });

  it('turns a national 07XXXXXXXX into 2567XXXXXXXX', () => {
    expect(ok('0700000013')).toBe('256700000013');
  });

  it('collapses a doubled 2560XXXXXXXXX to 256XXXXXXXXX', () => {
    expect(ok('2560700000014')).toBe('256700000014');
    expect(ok('+2560700000014')).toBe('256700000014');
  });

  it('puts 256 in front of a bare 9-digit number', () => {
    expect(ok('700000015')).toBe('256700000015');
  });

  it('keeps a full Uganda number as it is', () => {
    expect(ok('256700000016')).toBe('256700000016');
    expect(ok('+256700000016')).toBe('256700000016');
  });

  it('refuses a Uganda number that is not exactly 12 digits', () => {
    expect(refused('+25670000001')).toMatch(/256 and 9 digits/);
    expect(refused('2567000000177')).toMatch(/256 and 9 digits/);
  });

  it('refuses a leading 0 that would survive into E.164', () => {
    expect(refused('+0700000018')).toMatch(/0 where it cannot be/);
    expect(refused('07000000190')).toMatch(/0 where it cannot be/);
    expect(refused('070000001')).toMatch(/0 where it cannot be/);
    expect(refused('256070000001')).toMatch(/0 where it cannot be/);
  });

  it('accepts another country only with + and 8 to 15 digits', () => {
    expect(ok('+254 700 000 020')).toBe('254700000020');
    expect(refused('254700000020')).toMatch(/with \+ and the country code/);
    expect(refused('+4470090')).toMatch(/8 to 15 digits/);
    expect(refused('+4470090012345678')).toMatch(/8 to 15 digits/);
  });

  it('refuses an empty box and letters', () => {
    expect(refused('  ')).toMatch(/Type the customer/);
    expect(refused('0700 ABC 021')).toMatch(/digits only/);
  });
});

describe('whatsAppLink', () => {
  it('carries the digits and the encoded text', () => {
    expect(whatsAppLink('256700000022', '*Test Shop*: Receipt RS00000001\nThank you')).toBe(
      'https://wa.me/256700000022?text=*Test%20Shop*%3A%20Receipt%20RS00000001%0AThank%20you',
    );
  });
});

describe('receiptCaption', () => {
  const paid = { sale_no: 'RS00000001', payment_method: 'cash', paid_minor: 25000, balance_minor: 0 };

  it('names the business, the buyer, what was paid and that it is settled', () => {
    expect(receiptCaption(paid, 'Test Trading Ltd', 'Test Buyer 01')).toBe(
      ['*Test Trading Ltd*: Receipt RS00000001', 'Customer: Test Buyer 01', 'Amount paid: UGX 25,000 (Cash)', 'Fully settled, thank you!', 'Thank you for your business.'].join('\n'),
    );
  });

  it('leaves the customer line out when there is no buyer', () => {
    const text = receiptCaption(paid, 'Test Trading Ltd', undefined);
    expect(text).not.toContain('Customer:');
    expect(text.split('\n')).toHaveLength(4);
  });

  it('shows the balance still owed on a credit sale', () => {
    const text = receiptCaption({ sale_no: 'RS00000002', payment_method: 'credit', paid_minor: 10000, balance_minor: 15000 }, 'Test Trading Ltd', 'Test Buyer 02');
    expect(text).toContain('Amount paid: UGX 10,000 (Credit)');
    expect(text).toContain('Balance outstanding: UGX 15,000');
    expect(text).not.toContain('Fully settled');
  });

  it('never makes up a business name', () => {
    expect(receiptCaption(paid, undefined, undefined).split('\n')[0]).toBe('Receipt RS00000001');
  });
});
