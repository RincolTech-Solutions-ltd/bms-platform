// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Customer, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Share receipt and WhatsApp customer (issue #439, ADR-061) as a seller uses them: the PDF to the share sheet, the download
// where a browser cannot share files, a cancelled share sheet that shows nothing, the wa.me address, a refused number, who
// sees the buttons, and the optional save of a typed number. Fabricated names, numbers and amounts.

vi.setConfig({ testTimeout: 20000 });

const saleReceiptPdf = vi.fn();
const listCustomers = vi.fn();
const updateCustomer = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      saleReceiptPdf: (...a: unknown[]) => saleReceiptPdf(...a),
      listCustomers: (...a: unknown[]) => listCustomers(...a),
      updateCustomer: (...a: unknown[]) => updateCustomer(...a),
    },
  };
});

const { ShareReceipt, DOWNLOADED, WHATSAPP_HINT } = await import('./receipt-share');

const seller = mockMe('sales');
const admin = mockMe('admin');
const branch = seller.branches?.[0]?.id ?? '';
const sale: Sale = {
  id: 's1', sale_no: 'RS00000007', branch_id: branch, sale_date: '2026-10-22', payment_method: 'cash', status: 'completed',
  buyer_name: 'Test Buyer 01', buyer_contact: '0700 000 031', total_minor: 25000, paid_minor: 25000, balance_minor: 0, lines: [], created_by: seller.user_id,
};
const buyer: Customer = { id: 'c1', name: 'Test Buyer 02', version: 3 };

let open: ReturnType<typeof vi.fn>;
let click: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  saleReceiptPdf.mockResolvedValue(new Blob(['%PDF-1.4 test'], { type: 'application/pdf' }));
  listCustomers.mockResolvedValue([buyer]);
  open = vi.fn();
  vi.stubGlobal('open', open);
  URL.createObjectURL = vi.fn(() => 'blob:receipt');
  URL.revokeObjectURL = vi.fn();
  click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  click.mockRestore();
  saleReceiptPdf.mockReset();
  listCustomers.mockReset();
  updateCustomer.mockReset();
  delete (navigator as { share?: unknown }).share;
  delete (navigator as { canShare?: unknown }).canShare;
});

function screenOf(me: ReturnType<typeof mockMe>, s: Sale) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['branding'], { display_name: 'Test Trading Ltd' });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <ShareReceipt sale={s} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

function sharing(canShare: boolean, share: (data: ShareData) => Promise<void> = () => Promise.resolve()) {
  const shareFn = vi.fn(share);
  Object.defineProperty(navigator, 'share', { value: shareFn, configurable: true, writable: true });
  Object.defineProperty(navigator, 'canShare', { value: vi.fn(() => canShare), configurable: true, writable: true });
  return shareFn;
}

const shareButton = () => screen.getByRole('button', { name: 'Share receipt' });
const whatsAppButton = () => screen.getByRole('button', { name: 'WhatsApp customer' });
const phoneBox = () => screen.getByLabelText("Customer's WhatsApp number") as HTMLInputElement;

describe('Share receipt', () => {
  it('hands the PDF to the share sheet as a file, with the caption', async () => {
    const share = sharing(true);
    screenOf(seller, sale);
    fireEvent.click(shareButton());
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    expect(saleReceiptPdf).toHaveBeenCalledWith('s1');
    const data = share.mock.calls[0]?.[0] ?? {};
    expect(data.files?.[0]).toBeInstanceOf(File);
    expect(data.files?.[0]?.name).toBe('Receipt RS00000007.pdf');
    expect(data.files?.[0]?.type).toBe('application/pdf');
    expect(data.title).toBe('Receipt RS00000007');
    expect(data.text).toContain('*Test Trading Ltd*: Receipt RS00000007');
    expect(data.text).toContain('Fully settled, thank you!');
    expect(click).not.toHaveBeenCalled();
  });

  it('downloads the PDF and says so when the browser cannot share files', async () => {
    const share = sharing(false);
    screenOf(seller, sale);
    fireEvent.click(shareButton());
    expect(await screen.findByText(DOWNLOADED)).toBeTruthy();
    expect(share).not.toHaveBeenCalled();
    expect(click).toHaveBeenCalledTimes(1);
  });

  it('shows nothing when the share sheet is cancelled', async () => {
    const share = sharing(true, () => Promise.reject(new DOMException('cancelled', 'AbortError')));
    screenOf(seller, sale);
    fireEvent.click(shareButton());
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    await waitFor(() => expect((shareButton() as HTMLButtonElement).disabled).toBe(false));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });
});

describe('WhatsApp customer', () => {
  it('opens wa.me with the number on the sale and the caption, and says the PDF goes through Share receipt', () => {
    screenOf(seller, sale);
    expect(phoneBox().value).toBe('0700 000 031');
    expect(screen.getByText(WHATSAPP_HINT)).toBeTruthy();
    fireEvent.click(whatsAppButton());
    expect(open).toHaveBeenCalledTimes(1);
    const url = String(open.mock.calls[0]?.[0]);
    expect(url.startsWith('https://wa.me/256700000031?text=')).toBe(true);
    expect(decodeURIComponent(url.split('?text=')[1] ?? '')).toContain('Customer: Test Buyer 01');
  });

  it('refuses an ambiguous number in plain words and opens nothing', () => {
    screenOf(seller, sale);
    fireEvent.change(phoneBox(), { target: { value: '+25670000003' } });
    fireEvent.click(whatsAppButton());
    expect(screen.getByRole('alert').textContent).toMatch(/256 and 9 digits/);
    expect(open).not.toHaveBeenCalled();
  });

  it('offers to save a typed number on a buyer with no number, unticked, and saves it only when ticked', async () => {
    const onAccount: Sale = { ...sale, buyer_name: undefined, buyer_contact: undefined, customer_id: 'c1', payment_method: 'credit', paid_minor: 0, balance_minor: 25000 };
    updateCustomer.mockResolvedValue({ ...buyer, contact: '+256700000032' });
    screenOf(seller, onAccount);
    await waitFor(() => expect(listCustomers).toHaveBeenCalled());
    fireEvent.change(phoneBox(), { target: { value: '0700000032' } });
    const keep = (await screen.findByLabelText(/Save this number for Test Buyer 02/)) as HTMLInputElement;
    expect(keep.checked).toBe(false);
    fireEvent.click(keep);
    fireEvent.click(whatsAppButton());
    expect(String(open.mock.calls[0]?.[0])).toContain('Balance%20outstanding%3A%20UGX%2025%2C000');
    await waitFor(() => expect(updateCustomer).toHaveBeenCalledWith('c1', 3, { contact: '+256700000032' }));
  });

  it('does not save the number when the box is left unticked', async () => {
    const onAccount: Sale = { ...sale, buyer_contact: undefined, customer_id: 'c1' };
    screenOf(seller, onAccount);
    fireEvent.change(phoneBox(), { target: { value: '0700000033' } });
    await screen.findByLabelText(/Save this number for Test Buyer 02/);
    fireEvent.click(whatsAppButton());
    expect(open).toHaveBeenCalledTimes(1);
    expect(updateCustomer).not.toHaveBeenCalled();
  });
});

describe('who sees it', () => {
  it('is not offered to a seller for a sale another seller made, and is offered to an administrator', () => {
    const theirs: Sale = { ...sale, created_by: '00000000-0000-4000-8000-0000000000f9' };
    screenOf(seller, theirs);
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
    cleanup();
    screenOf(admin, theirs);
    expect(shareButton()).toBeTruthy();
  });

  it('is not offered for a voided sale', () => {
    screenOf(admin, { ...sale, status: 'voided' });
    expect(screen.queryByRole('button', { name: 'Share receipt' })).toBeNull();
  });
});
