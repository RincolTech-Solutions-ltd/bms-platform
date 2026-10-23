// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Field } from './Field';

// The one form field (#520): label above, hint, the error only once the field is left, described and marked invalid.

afterEach(cleanup);

function Phone({ showError = false }: { showError?: boolean }) {
  const [phone, setPhone] = useState('');
  const error = /^0\d{9}$/.test(phone) ? null : 'Type the 10 digits of the phone number, starting with 0.';
  return <Field label="Phone number" hint="The buyer gets the receipt here." inputMode="tel" autoComplete="tel" value={phone} onChange={(e) => setPhone(e.target.value)} error={error} showError={showError} />;
}

const input = () => screen.getByLabelText('Phone number') as HTMLInputElement;
const describedBy = () => document.getElementById(input().getAttribute('aria-describedby') ?? '')?.textContent;

describe('Field', () => {
  it('labels the input, passes its props, and is described by the hint', () => {
    render(<Phone />);
    expect(input().getAttribute('inputmode')).toBe('tel');
    expect(input().getAttribute('autocomplete')).toBe('tel');
    expect(input().getAttribute('aria-invalid')).toBe('false');
    expect(describedBy()).toBe('The buyer gets the receipt here.');
  });

  it('keeps the error back while the person types, and shows it in place of the hint once they leave', () => {
    render(<Phone />);
    input().focus();
    fireEvent.change(input(), { target: { value: '07' } });
    expect(screen.queryByText(/Type the 10 digits/)).toBeNull();
    fireEvent.blur(input());
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(describedBy()).toBe('Type the 10 digits of the phone number, starting with 0.');
    expect(screen.queryByText('The buyer gets the receipt here.')).toBeNull();
    fireEvent.change(input(), { target: { value: '0700000001' } });
    expect(input().getAttribute('aria-invalid')).toBe('false');
    expect(describedBy()).toBe('The buyer gets the receipt here.');
  });

  it('shows the error at once after a send', () => {
    render(<Phone showError />);
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText(/Type the 10 digits/).className).toBe('field-error');
  });

  it('says (needed) after the label of a required field and marks it aria-required, without the browser bubble', () => {
    render(<Field label="Buyer's name" required value="" onChange={() => undefined} />);
    const input = screen.getByLabelText(/Buyer's name/) as HTMLInputElement;
    expect(input.closest('.field')?.querySelector('label')?.textContent).toBe("Buyer's name (needed)");
    expect(input.getAttribute('aria-required')).toBe('true');
    expect(input.required).toBe(false);
  });

  it('wires another control through control', () => {
    render(
      <Field
        label="Branch"
        hint="Where the stock is."
        required
        control={(props) => (
          <select {...props}>
            <option>Main shop</option>
          </select>
        )}
      />,
    );
    const select = screen.getByLabelText(/^Branch/);
    expect(select.tagName).toBe('SELECT');
    expect(select.getAttribute('aria-required')).toBe('true');
    expect(document.getElementById(select.getAttribute('aria-describedby') ?? '')?.textContent).toBe('Where the stock is.');
  });
});
