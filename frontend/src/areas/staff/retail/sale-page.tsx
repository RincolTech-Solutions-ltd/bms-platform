import { createLazyRoute, getRouteApi, useNavigate, useRouter } from '@tanstack/react-router';
import { OpenSale, openedFromAllSales } from './sales';
import { Gate } from './ui';

// One sale at its own address, /staff/retail/sales/<id> (issue #336): what a sale number on another screen opens (`SaleLink`).
// It draws the same view All sales opens in place: the lines, payments, documents, returns and the actions on the sale. Its
// crumb is All sales and then the sale's number; See all sales goes back to the list.

/** The page for a sale id. The page tour test draws it before the sale arrives, so its anchors are on the wrapper and the way back. */
export function SalePageView({ saleId, onClose }: { saleId: string; onClose: () => void }) {
  return (
    <Gate screen="salesHistory" title="Sale">
      <section aria-label="The sale" data-tour="sale-page-record">
        <OpenSale id={saleId} onClose={onClose} page />
      </section>
    </Gate>
  );
}

const route = getRouteApi('/staff/retail/sales/$saleId');

/** The sale page's way back: Back when it was opened from All sales, so the list is found as it was left (issue #933). */
export function useCloseSale(saleId: string) {
  const navigate = useNavigate();
  const router = useRouter();
  return () => (openedFromAllSales(saleId) ? router.history.back() : void navigate({ to: '/staff/retail/sales' }));
}

function SalePage() {
  const { saleId } = route.useParams();
  return <SalePageView saleId={saleId} onClose={useCloseSale(saleId)} />;
}

export const Route = createLazyRoute('/staff/retail/sales/$saleId')({ component: SalePage });
