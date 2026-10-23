// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { MoneyInput, grouped } from './MoneyInput';

// The one amount field (#520): UGX in whole shillings, the numeric keypad, separators as you type, minor units out.

afterEach(cleanup);

let last: number | null | undefined;

function Paid({ start = null }: { start?: number | null }) {
  const [paid, setPaid] = useState<number | null>(start);
  last = paid;
  return <MoneyInput label="Amount paid" valueMinor={paid} onChange={setPaid} error={paid === null ? 'Type the amount paid.' : null} />;
}

const input = () => screen.getByLabelText('Amount paid') as HTMLInputElement;

describe('MoneyInput', () => {
  it('opens the numeric keypad and shows UGX beside the figures', () => {
    render(<Paid start={250000} />);
    expect(input().getAttribute('inputmode')).toBe('numeric');
    expect(input().value).toBe('250,000');
    expect(input().closest('.money-input')?.textContent).toContain('UGX');
  });

  it('groups the thousands as the person types and hands back whole shillings', () => {
    render(<Paid />);
    fireEvent.change(input(), { target: { value: '1250' } });
    expect(input().value).toBe('1,250');
    expect(last).toBe(1250);
    fireEvent.change(input(), { target: { value: '1,2500' } });
    expect(input().value).toBe('12,500');
    expect(last).toBe(12500);
  });

  it('drops anything but digits, decimals included, and leading zeros', () => {
    render(<Paid />);
    fireEvent.change(input(), { target: { value: '00a5.50' } });
    expect(input().value).toBe('550');
    expect(last).toBe(550);
  });

  it('hands back null when emptied, and shows the error once the field is left', () => {
    render(<Paid start={500} />);
    fireEvent.change(input(), { target: { value: '' } });
    expect(last).toBeNull();
    expect(input().getAttribute('aria-invalid')).toBe('false');
    fireEvent.blur(input());
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText('Type the amount paid.')).toBeTruthy();
  });

  it('groups through the one money formatter', () => {
    expect(grouped('')).toBe('');
    expect(grouped('5')).toBe('5');
    expect(grouped('1000000')).toBe('1,000,000');
  });
});
