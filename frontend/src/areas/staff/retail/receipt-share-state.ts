import type { Sale } from '../../../api/retail';
import { money } from './ui';

// Sharing a sale's receipt from the seller's own phone (issue #439, ADR-061). Nothing here talks to a server: the phone
// rules and the caption are pure, so they are tested on their own. The phone rules are ported from the company's other
// product (its receipt sender's number normaliser): a number that could mean two things is refused, never guessed.

export type PhoneCheck = { ok: true; digits: string } | { ok: false; problem: string };

const UGANDA = '256';

/**
 * A phone number as WhatsApp takes it: E.164 digits without the plus (`256` and 9 digits for Uganda), or the reason it is
 * refused. Spaces, dashes, brackets and dots are dropped; `00` is the international prefix; a national `07XXXXXXXX` and a
 * bare 9-digit number are Ugandan; a doubled `2560...` collapses; a Ugandan number is exactly 12 digits; any other country
 * needs the `+` and 8 to 15 digits; a leading 0 never survives.
 */
export function normalisePhone(typed: string): PhoneCheck {
  let s = typed.trim().replace(/[\s\-().]/g, '');
  if (s === '') return { ok: false, problem: "Type the customer's phone number." };
  let international = false;
  if (s.startsWith('+')) {
    international = true;
    s = s.slice(1);
  } else if (s.startsWith('00')) {
    international = true;
    s = s.slice(2);
  }
  if (!/^\d+$/.test(s)) return { ok: false, problem: 'A phone number has digits only, and a + only at the start.' };
  if (/^2560\d{9}$/.test(s)) s = UGANDA + s.slice(4);
  else if (!international && /^0\d{9}$/.test(s)) s = UGANDA + s.slice(1);
  else if (!international && /^\d{9}$/.test(s)) s = UGANDA + s;
  if (s.startsWith('0') || s.startsWith(`${UGANDA}0`)) return { ok: false, problem: 'This number has a 0 where it cannot be: at the start, or right after 256. Check it and type it again.' };
  if (s.startsWith(UGANDA)) {
    if (s.length !== 12) return { ok: false, problem: `A Uganda number is 256 and 9 digits, like 0700 000 000. This one has ${s.length - 3} digits after 256.` };
    return { ok: true, digits: s };
  }
  if (!international) return { ok: false, problem: 'For a number outside Uganda, type it with + and the country code.' };
  if (s.length < 8 || s.length > 15) return { ok: false, problem: 'A number with a country code has 8 to 15 digits. Check it and type it again.' };
  return { ok: true, digits: s };
}

/** The number to show a person: `+` and the digits. */
export const shownPhone = (digits: string) => `+${digits}`;

/** The wa.me address that opens a chat with the number and the message typed in. A wa.me link cannot carry a file. */
export function whatsAppLink(digits: string, text: string): string {
  return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`;
}

const METHODS: Record<string, string> = { cash: 'Cash', mobile_money: 'Mobile money', bank: 'Bank', credit: 'Credit' };

/**
 * The words that go with the receipt, in the share sheet and in WhatsApp: the BUSINESS's own name (never the platform's),
 * the receipt number, the buyer when there is one, what was paid and how, and what is still owed.
 */
export function receiptCaption(sale: Pick<Sale, 'sale_no' | 'paid_minor' | 'balance_minor' | 'payment_method'>, business: string | undefined, buyer: string | undefined): string {
  const name = business?.trim();
  const who = buyer?.trim();
  const owed = sale.balance_minor ?? 0;
  const method = METHODS[sale.payment_method ?? ''] ?? sale.payment_method ?? '';
  return [
    `${name ? `*${name}*: ` : ''}Receipt ${sale.sale_no ?? ''}`.trim(),
    ...(who ? [`Customer: ${who}`] : []),
    `Amount paid: ${money(sale.paid_minor ?? 0)}${method ? ` (${method})` : ''}`,
    owed > 0 ? `Balance outstanding: ${money(owed)}` : 'Fully settled, thank you!',
    'Thank you for your business.',
  ].join('\n');
}
