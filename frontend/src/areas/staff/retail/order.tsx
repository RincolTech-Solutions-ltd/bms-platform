import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useParams } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday } from '../../../api/retail';
import { retailOrders, type Order } from '../../../api/retail-orders';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { OrderActions, OrderSummary } from './orders';
import { feeOf, isOpen } from './orders-state';
import { canUse } from './permissions';
import { OpenSale } from './sales';
import { Gate, Problem, useBranchName } from './ui';

// One customer order (issue #385, ADR-052): what was ordered, where it stands, who did what and when, the delivery details that
// can still change, and, once delivered, the sale it became (with its receipt, payments, returns and void like any sale).

const STEP_WORDS: Record<string, string> = {
  ordered: 'Order taken',
  changed: 'Details changed',
  packed: 'Packed',
  out_for_delivery: 'Out for delivery',
  delivered: 'Delivered',
  cancelled: 'Cancelled',
};

function ChangeDetails({ order }: { order: Order }) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(order.customer_name ?? '');
  const [phone, setPhone] = useState(order.customer_phone ?? '');
  const [place, setPlace] = useState(order.delivery_place ?? '');
  const [date, setDate] = useState(order.delivery_date ?? '');
  const [fee, setFee] = useState(String(order.delivery_fee_minor ?? 0));
  const [notes, setNotes] = useState(order.notes ?? '');
  const save = useMutation({
    mutationFn: () => retailOrders.change(order.id ?? '', order.version ?? 1, {
      customer_name: name.trim(),
      customer_phone: phone.trim(),
      delivery_place: place.trim(),
      ...(date ? { delivery_date: date } : {}),
      delivery_fee_minor: feeOf(fee) ?? 0,
      notes: notes.trim(),
    }),
    onSuccess: () => {
      setOpen(false);
      void client.invalidateQueries({ queryKey: ['retail', 'order', order.id] });
      void client.invalidateQueries({ queryKey: ['retail', 'orders'] });
    },
  });
  const problem = name.trim() === '' ? 'Type the name of the customer.' : feeOf(fee) === null ? 'The delivery fee is a whole number of shillings.' : null;
  if (!open) return <button type="button" className="btn-sm" data-tour="order-change" onClick={() => setOpen(true)}>Change the delivery details</button>;
  return (
    <fieldset data-tour="order-change">
      <legend>Change the delivery details</legend>
      <label htmlFor="change-name">Name</label>
      <input id="change-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="change-phone">Phone number</label>
      <input id="change-phone" inputMode="tel" value={phone} maxLength={100} onChange={(e) => setPhone(e.target.value)} />
      <label htmlFor="change-place">Deliver to</label>
      <input id="change-place" value={place} maxLength={300} onChange={(e) => setPlace(e.target.value)} />
      <label htmlFor="change-date">Delivery day</label>
      <input id="change-date" type="date" min={businessToday()} value={date} onChange={(e) => setDate(e.target.value)} />
      <label htmlFor="change-fee">Delivery fee</label>
      <input id="change-fee" inputMode="numeric" value={fee} onChange={(e) => setFee(e.target.value)} />
      <label htmlFor="change-notes">Notes</label>
      <input id="change-notes" value={notes} maxLength={500} onChange={(e) => setNotes(e.target.value)} />
      <p className="hint">The items cannot change. To change them, cancel this order and take a new one.</p>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={save.error} />
      <button type="button" disabled={problem !== null || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving' : 'Save the changes'}</button>
      <button type="button" onClick={() => setOpen(false)}>Close</button>
    </fieldset>
  );
}

export function OrderPage({ orderId }: { orderId: string }) {
  const { me } = useStaff();
  const nameOf = useBranchName();
  const [saleOpen, setSaleOpen] = useState(false);
  const order = useQuery({ queryKey: ['retail', 'order', orderId], queryFn: () => retailOrders.get(orderId) });
  const o = order.data;
  useCrumbLabel(o?.order_no ? `Order ${o.order_no}` : undefined);
  return (
    <Gate screen="orders" title="Order">
      <p className="rt-row" data-tour="order-back">
        <Link className="btn-sm" to="/staff/retail/orders">Back to the orders</Link>
        {canUse(me, 'ordersReport') && <Link className="btn-sm" to="/staff/retail/orders-report">Delivery fees and cancellations</Link>}
      </p>
      {order.isPending && <p className="loading">Loading</p>}
      <Problem error={order.error} />
      <section className="rt-card" data-tour="order-summary" aria-label="The order">
        {o && <OrderSummary order={o} branchName={nameOf(o.branch_id)} />}
        {o && isOpen(o) && <OrderActions order={o} />}
      </section>
      {o && isOpen(o) && <ChangeDetails key={o.version} order={o} />}
      {o?.sale_id && !saleOpen && (
        <p><button type="button" className="btn-sm" onClick={() => setSaleOpen(true)}>Open sale {o.sale_no}</button></p>
      )}
      {o?.sale_id && saleOpen && <OpenSale id={o.sale_id} onClose={() => setSaleOpen(false)} />}
      <section data-tour="order-history" aria-label="History">
        <h2>History</h2>
        {o && (
          <ol>
            {(o.history ?? []).map((e, i) => (
              <li key={`${e.at}-${i}`}>
                <strong>{STEP_WORDS[e.step ?? ''] ?? e.step}</strong>, {new Date(e.at ?? '').toLocaleString()}{e.by_name ? `, by ${e.by_name}` : ''}
                {e.note ? <span className="hint">: {e.note}</span> : null}
              </li>
            ))}
          </ol>
        )}
      </section>
    </Gate>
  );
}

function OrderRoute() {
  const { orderId } = useParams({ from: '/staff/retail/orders/$orderId' });
  return <OrderPage orderId={orderId} />;
}

export const Route = createLazyRoute('/staff/retail/orders/$orderId')({ component: OrderRoute });
