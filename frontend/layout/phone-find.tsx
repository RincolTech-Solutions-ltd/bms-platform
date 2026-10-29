import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Category, Product } from '../src/api/retail';
import { RowList } from '../src/app/ui/ListRow';
import { SearchBox } from '../src/app/ui/SearchBox';
import { useLiveSearch } from '../src/app/ui/useLiveSearch';
import { CategoryChips, FindBar, ItemRow } from '../src/areas/staff/retail/phone-find';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page phone-find.layout.test.ts lays out in a real browser (issue #690): the staff bar, then the search and the category
// chips of Items under it, then a long list of item rows. ?font= scales the root font size as a phone's large text setting does.
// Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const names = ['Test Cables and wires', 'Test Lighting', 'Test Fittings and switches', 'Test Solar panels', 'Test Hardware', 'Test Plumbing', 'Test Paint', 'Test Tools', 'Test Garden'];
const categories: Category[] = names.map((name, i) => ({ id: `c${i}`, name, active: true, product_count: 3 + i * 7 }));
const items: Product[] = Array.from({ length: 30 }, (_, i) => ({
  id: `p${i}`,
  code: `TST-${String(i + 1).padStart(4, '0')}`,
  description: i % 3 === 0 ? `Test LED bulb ${i}W screw cap, warm white, long life` : `Test item ${i + 1}`,
  category: names[i % names.length],
  unit: 'pc',
  qty: `${1127 - i * 13}.000`,
  sell_minor: 4500 + i * 1000,
  packs: [{ id: `k${i}`, unit_id: 'u2', unit: 'Carton', factor: '12.000', active: true, standard: true }],
}));

function Page() {
  const search = useLiveSearch();
  const [chosen, setChosen] = useState(query.get('chip') ?? '');
  return (
    <div className="app-shell"><div className="app-content"><div className="nav-layout">
      <div className="nav-layout-main">
        <header className="staff-bar"><div className="staff-bar-top"><strong>Test Shop Ntinda</strong></div></header>
        <h1>Items</h1>
        {/* My stock's two entries above its find bar, as stock.tsx draws them. */}
        <nav className="find-entries" aria-label="Items and categories">
          <a className="link-row" href="#items">Items</a>
          <a className="link-row" href="#categories">Categories</a>
        </nav>
        <FindBar>
          <SearchBox id="items-search" label="Search by name, code, barcode or category" search={search} fetching={false} count={items.length} />
          <CategoryChips categories={categories} value={chosen} onChange={setChosen} />
        </FindBar>
        <RowList label="Items">{items.map((p) => <ItemRow key={p.id} product={p} onOpen={() => undefined} />)}</RowList>
      </div>
    </div></div></div>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(<Page />);
