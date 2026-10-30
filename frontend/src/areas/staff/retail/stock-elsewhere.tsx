import { useQuery } from '@tanstack/react-query';
import type { Me } from '../../../api/client';
import { retail, type AllBranchesStock, type Product } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useStaff } from '../context';
import { milliOf, showQty } from './maths';
import { branchesWhere, holdsAt } from './permissions';

// Where the stock is (issue #728): a shop owner added his items at his store and looked at My stock in his main shop, where they
// showed 0, and thought his numbers were lost. An item with none here and some at another shop says so, "0 here. 456 at Test Store",
// with one tap to switch there, on My stock and on the item's page. The other shops come from the all-branches stock list, which
// the server cuts to the branches this person may read, and only a shop in their branch list is offered.

/** Another shop holding an item: its id, its name and how many it holds. */
export type Elsewhere = { branchId: string; name: string; qty: string };

/** The shops, other than `here`, where this person may read stock and goods are kept. */
export function otherStockShops(me: Me, here: string | null) {
  return branchesWhere(me, 'retail.stock.read').filter((b) => b.id && b.id !== here && b.stocks_goods !== false);
}

/** For each item, the other shop holding the most of it, among `shops`; items held nowhere else are left out. */
export function stockElsewhere(data: AllBranchesStock | undefined, here: string, shops: { id?: string; name?: string; code?: string }[]): Map<string, Elsewhere> {
  const out = new Map<string, Elsewhere>();
  const byId = new Map(shops.map((b) => [b.id ?? '', b]));
  for (const item of data?.items ?? []) {
    const best = (item.balances ?? [])
      .filter((c) => c.branch_id !== here && byId.has(c.branch_id ?? '') && milliOf(c.qty ?? '0') > 0)
      .sort((x, y) => milliOf(y.qty ?? '0') - milliOf(x.qty ?? '0'))[0];
    const shop = best && byId.get(best.branch_id ?? '');
    if (best && shop) out.set(item.product_id ?? '', { branchId: best.branch_id ?? '', name: shop.name || branchLabel(shop), qty: best.qty ?? '0' });
  }
  return out;
}

/** True for a quantity of exactly none, the one "0 here" speaks of; a negative balance keeps its own flag. */
export const noneHere = (qty: string | undefined) => milliOf(qty ?? '0') === 0;

/** "0 here. 456 at Test Store" and Switch to Test Store, which makes that shop the one in use. */
export function ElsewhereNote({ at, id }: { at: Elsewhere; id: string }) {
  const { chooseBranch } = useStaff();
  return (
    <div className="stock-elsewhere" data-testid={`elsewhere-${id}`}>
      <p>{`0 here. ${showQty(at.qty)} at ${at.name}`}</p>
      {chooseBranch && <button type="button" onClick={() => chooseBranch(at.branchId)}>{`Switch to ${at.name}`}</button>}
    </div>
  );
}

/**
 * The item's page (issue #728): with none of it in the shop in use and some at another shop this person may read, the same note
 * as My stock. Nothing is read for someone who may not read stock here or has no other shop.
 */
export function ItemElsewhere({ product }: { product: Product }) {
  const { me, branch } = useStaff();
  const shops = otherStockShops(me, branch);
  const enabled = Boolean(product.code) && branch !== null && holdsAt(me, 'retail.stock.read', branch) && shops.length > 0;
  const stock = useQuery({
    queryKey: ['retail', 'stock', 'all-branches', 'item', product.id],
    queryFn: ({ signal }) => retail.listStockAllBranches({ query: product.code ?? '', includeInactive: true, signal }),
    enabled,
  });
  if (!enabled || !stock.data || branch === null) return null;
  const row = stock.data.items.find((r) => r.product_id === product.id);
  const here = row?.balances?.find((c) => c.branch_id === branch);
  if (!row || (here && !noneHere(here.qty))) return null;
  const at = stockElsewhere({ ...stock.data, items: [row] }, branch, shops).get(product.id ?? '');
  return at ? <ElsewhereNote at={at} id={product.id ?? ''} /> : null;
}
