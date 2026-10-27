import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useMemo, useState } from 'react';
import { retail, type Category, type Unit } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Hint } from '../../../app/ui/Hint';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { Segmented } from '../../../app/ui/Segmented';
import { Skeleton } from '../../../app/ui/Skeleton';
import { SuccessScreen } from '../../../app/ui/SuccessScreen';
import { useOnline } from '../../../offline/offline-context';
import { factorProblem } from './catalogue-packs';
import { SHOP_TYPES, shopTypeById, type PackUse } from './shop-starters';
import { USE_WORDS, applySetup, key, packNeeds, packText, planSetup, type SetupItem, type SetupResult } from './shop-setup-state';
import { Gate, Problem } from './ui';

// Set up your shop (issue #625, docs/specs/units-packs-and-costing.md section 9 step 5): a new shop owner should not build
// categories and units one by one. The owner says what kind of shop it is; the app lists that kind's categories, units and
// packs with their usual size, each ticked; the owner unticks what they do not want, changes a size or how a pack is used, and
// taps Add these. The lists are bundled (shop-starters.ts), so they show offline; adding needs the network. Names the shop has
// are shown as already there and never added twice. Prices are never set here: each item's packs get their prices on the item.

export const SET_UP_TITLE = 'Set up your shop';
export const NOTHING_NEW = 'Your shop has all of these already.';
export const OFFLINE_ADD = 'You are offline. Connect to the internet to add these.';

const USES: { value: PackUse; label: string }[] = (['buying', 'selling', 'both'] as const).map((value) => ({ value, label: USE_WORDS[value] }));

/** What one pack row may change before it is added: its usual size and what it is for. */
type PackEdit = { size: string; use: PackUse };

export function addedText(result: SetupResult): string {
  const count = (kind: string) => result.added.filter((a) => a.kind === kind).length;
  const part = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  return `Added ${part(count('category'), 'category', 'categories')}, ${part(count('unit'), 'unit', 'units')} and ${part(count('pack'), 'pack', 'packs')}.`;
}

function Choice({ item, ticked, onTick, meta }: { item: SetupItem; ticked: boolean; onTick: (on: boolean) => void; meta?: string }) {
  const id = `setup-${key(item.kind, item.name).replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <label htmlFor={id}>
      <input id={id} type="checkbox" checked={ticked} onChange={(e) => onTick(e.target.checked)} /> {item.name}
      {meta && <span className="hint"> {meta}</span>}
    </label>
  );
}

export function ShopSetupPage() {
  const queryClient = useQueryClient();
  const online = useOnline();
  const [typeId, setTypeId] = useState('');
  const [unticked, setUnticked] = useState<Set<string>>(new Set());
  const [edits, setEdits] = useState<Record<string, PackEdit>>({});
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SetupResult | null>(null);
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const type = shopTypeById(typeId);
  const loading = categories.isPending || units.isPending;
  // Offline before the lists ever came, the bundled set still shows; what the shop has is checked again when Add these runs.
  const plan = useMemo(() => (type ? planSetup(type, categories.data ?? [], units.data ?? []) : []), [type, categories.data, units.data]);
  const open = plan.filter((i) => i.status !== 'there');
  const there = plan.filter((i) => i.status === 'there');
  const ticked = new Set(open.map((i) => key(i.kind, i.name)).filter((k) => !unticked.has(k)));
  const withEdits = (i: SetupItem): SetupItem => {
    const e = edits[key(i.kind, i.name)];
    return e ? { ...i, size: Number(e.size), use: e.use } : i;
  };
  const sizeProblem = (i: SetupItem) => factorProblem(edits[key(i.kind, i.name)]?.size ?? String(i.size));
  const chosen = open.filter((i) => ticked.has(key(i.kind, i.name)) && !packNeeds(i, ticked, units.data ?? []));
  const firstBadSize = chosen.find((i) => i.kind === 'pack' && sizeProblem(i) !== null);

  const tick = (i: SetupItem, on: boolean) => setUnticked((was) => {
    const next = new Set(was);
    if (on) next.delete(key(i.kind, i.name)); else next.add(key(i.kind, i.name));
    return next;
  });
  const edit = (i: SetupItem, change: Partial<PackEdit>) => setEdits((was) => {
    const k = key(i.kind, i.name);
    return { ...was, [k]: { size: was[k]?.size ?? String(i.size), use: was[k]?.use ?? i.use ?? 'both', ...change } };
  });

  const add = async () => {
    if (!type || busy) return;
    if (firstBadSize) {
      document.getElementById(`setup-size-${firstBadSize.name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`)?.focus();
      return;
    }
    setBusy(true);
    try {
      // Read what the shop has now, not what was on screen, so a name another phone added meanwhile is skipped too.
      const [nowCategories, nowUnits] = await Promise.all([
        queryClient.fetchQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories(), staleTime: 0 }) as Promise<Category[]>,
        queryClient.fetchQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits(), staleTime: 0 }) as Promise<Unit[]>,
      ]);
      const fresh = planSetup(type, nowCategories, nowUnits).map(withEdits);
      setResult(await applySetup(retail, fresh, ticked, nowUnits));
    } catch (error) {
      setResult({ added: [], skipped: [], error });
    } finally {
      setBusy(false);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    }
  };

  if (result && !result.error) {
    return (
      <Gate screen="shopSetup" title={SET_UP_TITLE}>
        <SuccessScreen
          level={2}
          title={result.added.length > 0 ? 'Your shop is set up' : 'Nothing new was added'}
          detail={result.added.length > 0 ? addedText(result) : NOTHING_NEW}
          actions={[{ label: 'Add your first items', to: '/staff/retail/catalogue/products' }, { label: 'Done', to: '/staff/retail/catalogue' }]}
        />
        {result.added.length > 0 && (
          <Card title="What was added">
            <RowList label="What was added">
              {result.added.map((a) => <ListRow key={`${a.kind}-${a.name}`} title={a.name} meta={a.detail ?? (a.kind === 'unit' ? 'Unit' : 'Category')} />)}
            </RowList>
          </Card>
        )}
      </Gate>
    );
  }

  return (
    <Gate screen="shopSetup" title={SET_UP_TITLE}>
      <fieldset data-tour="setup-type">
        <legend>What kind of shop is it?</legend>
        {SHOP_TYPES.map((t) => (
          <label key={t.id} htmlFor={`setup-type-${t.id}`}>
            <input id={`setup-type-${t.id}`} type="radio" name="setup-type" value={t.id} checked={typeId === t.id} onChange={() => { setTypeId(t.id); setUnticked(new Set()); setEdits({}); setResult(null); }} />{' '}
            {t.label} <span className="hint">{t.hint}</span>
          </label>
        ))}
      </fieldset>
      {type && (
        <Skeleton loading={loading && online} of={type.id}>
          <Hint>Untick what you do not sell, then tap Add these. Names your shop has are left as they are.</Hint>
          {open.length === 0 ? (
            <p className="empty-state" data-testid="setup-nothing-new">{NOTHING_NEW}</p>
          ) : (
            <div className="stack" data-tour="setup-list">
              {open.some((i) => i.kind === 'category') && (
                <Card title="Categories">
                  <Hint>The app makes the code letters of each category from its name, like BLB for Bulbs.</Hint>
                  {open.filter((i) => i.kind === 'category').map((i) => <Choice key={i.name} item={i} ticked={ticked.has(key(i.kind, i.name))} onTick={(on) => tick(i, on)} />)}
                </Card>
              )}
              {open.some((i) => i.kind === 'unit') && (
                <Card title="Units">
                  {open.filter((i) => i.kind === 'unit').map((i) => <Choice key={i.name} item={i} ticked={ticked.has(key(i.kind, i.name))} onTick={(on) => tick(i, on)} />)}
                </Card>
              )}
              {open.some((i) => i.kind === 'pack') && (
                <Card title={WORDS.packs}>
                  <Hint>The usual size is a starting number. Each item can have its own, and its own prices.</Hint>
                  {open.filter((i) => i.kind === 'pack').map((i) => {
                    const k = key(i.kind, i.name);
                    const on = ticked.has(k);
                    const needs = packNeeds(i, ticked, units.data ?? []);
                    const e = edits[k] ?? { size: String(i.size), use: i.use ?? 'both' };
                    const slug = i.name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
                    const problem = factorProblem(e.size);
                    return (
                      <div key={i.name} className="stack" data-testid={`setup-pack-${slug}`}>
                        <Choice item={i} ticked={on} onTick={(v) => tick(i, v)} meta={needs ? `Tick ${needs} too, or this pack is left out.` : packText(e.size, i.of ?? '', e.use)} />
                        {on && !needs && (
                          <>
                            <label htmlFor={`setup-size-${slug}`}>{`${i.of} in one ${i.name}`}</label>
                            <input id={`setup-size-${slug}`} inputMode="decimal" value={e.size} onChange={(ev) => edit(i, { size: ev.target.value })} aria-invalid={problem !== null} aria-describedby={problem !== null ? `setup-size-problem-${slug}` : undefined} />
                            {problem !== null && <p id={`setup-size-problem-${slug}`} className="rt-flag">{problem}</p>}
                            <p className="hint">How do you buy and sell this?</p>
                            <Segmented label={`How do you buy and sell a ${i.name}?`} options={USES} value={e.use} onChange={(use) => edit(i, { use })} />
                          </>
                        )}
                      </div>
                    );
                  })}
                </Card>
              )}
            </div>
          )}
          {there.length > 0 && <p className="hint" data-testid="setup-there">Already in your shop: {there.map((i) => i.name).join(', ')}.</p>}
          {result?.error != null && (
            <>
              {result.added.length > 0 && <p data-testid="setup-partly">{`${addedText(result)} Tap Add these again for the rest.`}</p>}
              <Problem error={result.error} />
            </>
          )}
          {!online && <p className="hint" data-testid="setup-offline">{OFFLINE_ADD}</p>}
          {open.length > 0 && (
            <ActionBar>
              <BusyButton busy={busy} type="button" className="rt-primary" data-tour="setup-add" disabled={!online || chosen.length === 0} notReady={firstBadSize !== undefined} onClick={() => void add()}>
                {chosen.length > 0 ? `Add these (${chosen.length})` : 'Add these'}
              </BusyButton>
            </ActionBar>
          )}
        </Skeleton>
      )}
    </Gate>
  );
}

export const ShopSetupRoute = createLazyRoute('/staff/retail/catalogue/set-up')({ component: ShopSetupPage });
