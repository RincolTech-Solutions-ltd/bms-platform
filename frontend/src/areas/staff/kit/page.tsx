import { createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { Field } from '../../../app/ui/Field';
import { MoneyInput } from '../../../app/ui/MoneyInput';
import { Segmented } from '../../../app/ui/Segmented';
import { Stepper } from '../../../app/ui/Stepper';
import { formatMinor } from '../../../components/money';
import { isAdmin } from '../../../offline/offline-context';
import { useStaff } from '../context';

// The component kit page (#520): every control of src/app/ui/kit.css on one staff page, with made-up figures, so a change to the
// kit is reviewed on a phone in one place before phase 3 moves the screens onto it. Administrators only, and not in the menu:
// it is reached by typing /staff/kit. It calls no API.

function Example({ title, about, children }: { title: string; about: string; children: ReactNode }) {
  return (
    <section className="kit-example" aria-labelledby={`kit-${title}`}>
      <h2 id={`kit-${title}`}>{title}</h2>
      <p className="hint">{about}</p>
      {children}
    </section>
  );
}

const SHOW = [
  { value: 'all', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
] as const;

const ugx = (minor: number) => formatMinor(minor, 'UGX');

function KitPage() {
  const { me } = useStaff();
  const [show, setShow] = useState<string>('all');
  const [qty, setQty] = useState('2');
  const [phone, setPhone] = useState('');
  const [paid, setPaid] = useState<number | null>(null);
  if (!isAdmin(me)) {
    return (
      <main>
        <h1>Component kit</h1>
        <p className="lead">This page is for administrators.</p>
      </main>
    );
  }
  return (
    <main className="kit-page">
      <h1>Component kit</h1>
      <p className="lead">The shared controls every screen will use. The figures are made up.</p>

      <Example title="Segmented" about="Pick one of 2 to 5. Replaces rows of separate buttons.">
        <Segmented label="Show" options={SHOW} value={show} onChange={setShow} />
      </Example>

      <Example title="Stepper" about="A quantity, from 1 to 99. Tap minus or plus, type a number, or use the arrow keys.">
        <Stepper id="kit-qty" label="Quantity (piece)" name="Sample soap" value={qty} onChange={setQty} min={1} max={99} />
      </Example>

      <Example title="Field" about="The label above, a hint under it, and the error only once you leave the field.">
        <Field
          label="Buyer's phone number"
          hint="The receipt is sent here."
          inputMode="tel"
          autoComplete="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          error={/^0\d{9}$/.test(phone) ? null : 'Type the 10 digits of the number, starting with 0.'}
        />
      </Example>

      <Example title="MoneyInput" about="Whole shillings on the number keypad, with the commas drawn as you type.">
        <MoneyInput label="Amount paid" hint="What the buyer handed over." valueMinor={paid} onChange={setPaid} error={paid === null ? 'Type the amount paid.' : null} />
      </Example>

      <Example title="ListRow and Card" about="One row style for items, sales and people. The whole row is the target.">
        <Card title="Today's sales">
          <RowList label="Today's sales">
            <ListRow title="Sale S-000101" meta="10:42, walk-in buyer" amount={ugx(12500)} to="/staff/kit" />
            <ListRow title="Sale S-000102" meta="11:05, Sample Buyer, on credit" amount={ugx(184000)} to="/staff/kit" />
            <ListRow title="Sample soap" meta="12 left in Main shop" amount={ugx(3000)} />
            <ListRow title="Sample Person" meta="Seller, Main shop" onClick={() => undefined} />
          </RowList>
        </Card>
      </Example>
    </main>
  );
}

export const Route = createLazyRoute('/staff/kit')({ component: KitPage });
