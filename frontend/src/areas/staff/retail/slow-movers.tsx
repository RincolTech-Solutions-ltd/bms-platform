import { Link, useRouter } from '@tanstack/react-router';
import { useContext } from 'react';
import type { SlowMovers } from '../../../api/retail-analytics';
import { StaffContext } from '../context';
import { Empty, Section, ToStock } from './analytics-ui';
import { showQty } from './maths';
import { canUse } from './permissions';
import { moveStockSearch, monthsText } from './slow-movers-state';
import { money, useIsPhone } from './ui';

// Slow movers (issue #414): items that do sell, but slowly, apart from dead stock. Cost figures (and the
// total) are in the body only for a caller with retail.profit.read, so they show only when sent.

type NameOf = (id: string | undefined) => string;

/** The suggestion to move stock where the item sells faster, with a link that fills the Move stock form in. */
function SellsBetter({ item, nameOf }: { item: NonNullable<SlowMovers['items']>[number]; nameOf: NameOf }) {
  const staff = useContext(StaffContext);
  const router = useRouter({ warn: false });
  const better = item.sells_better_at;
  const search = moveStockSearch(item);
  if (!better || !search) return null;
  const text = `Sells better at ${nameOf(better.branch_id)}: move ${showQty(better.suggested_qty ?? '0')} ${item.unit}.`;
  const mayMove = router && staff && canUse(staff.me, 'transfer');
  return (
    <span className="hint" data-testid="sells-better">
      {text}{' '}
      {mayMove && <Link to={'/staff/retail/transfer' as never} search={search as never}>Move stock</Link>}
    </span>
  );
}

export function SlowMoversSection({ slow, nameOf }: { slow: SlowMovers | undefined; nameOf: NameOf }) {
  const phone = useIsPhone();
  const items = slow?.items ?? [];
  const withCost = items.some((i) => i.value_at_cost_minor !== undefined);
  return (
    <Section
      title="Slow movers: stock that sells slowly"
      hint={`Items that sell, but would take more than ${slow?.slow_months ?? 4} months to sell at the pace of the last ${slow?.days ?? 90} days, or sold fewer than ${slow?.min_sales ?? 3} times in that time. Items that did not sell at all are under Dead stock.`}
    >
      {items.length === 0 ? <Empty>No slow movers. Everything that sells is moving at a good pace.</Empty> : (
        <>
          <p>
            <strong>{slow?.items_total ?? items.length}</strong> {(slow?.items_total ?? items.length) === 1 ? 'item sells' : 'items sell'} slowly.
            {slow?.value_at_cost_minor !== undefined && <> Money tied up in slow stock, at cost: <strong>{money(slow.value_at_cost_minor)}</strong>.</>}
          </p>
          <div className="table-wrap" tabIndex={0}><table>
            <thead>
              <tr>
                <th>Item</th>
                {!phone && <th className="num">In stock</th>}
                {!phone && <th>Last sold</th>}
                <th className="num">Months to sell</th>
                {withCost && !phone && <th className="num">Tied up at cost</th>}
              </tr>
            </thead>
            <tbody>
              {items.map((i) => (
                <tr key={`${i.branch_id}-${i.product_id}`}>
                  <td>
                    <ToStock>{i.description}</ToStock><br />
                    <span className="hint">{i.code}, {nameOf(i.branch_id)}</span>
                    {phone && <><br /><span className="hint">{showQty(i.qty_on_hand ?? '0')} {i.unit} in stock, last sold {i.last_sold_on}</span></>}
                    {phone && i.value_at_cost_minor !== undefined && <><br /><span className="hint">cost tied up {money(i.value_at_cost_minor)}</span></>}
                    {i.sells_better_at && <><br /><SellsBetter item={i} nameOf={nameOf} /></>}
                  </td>
                  {!phone && <td className="num">{showQty(i.qty_on_hand ?? '0')} {i.unit}</td>}
                  {!phone && <td>{i.last_sold_on}</td>}
                  <td className="num">{monthsText(i.months_to_sell, i.capped)}</td>
                  {withCost && !phone && <td className="num">{i.value_at_cost_minor === undefined ? '' : money(i.value_at_cost_minor)}</td>}
                </tr>
              ))}
            </tbody>
          </table></div>
          {(slow?.items_total ?? 0) > items.length && <p className="hint">Showing {items.length} of {slow?.items_total}. Raise the Top number to see more.</p>}
        </>
      )}
    </Section>
  );
}
