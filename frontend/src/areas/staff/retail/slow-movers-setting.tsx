import { useMutation, useQueryClient } from '@tanstack/react-query';
import { retail, type CapabilitiesRead } from '../../../api/retail';
import { Choice } from './analytics-ui';
import { Problem, changeFailureText } from './ui';

// The months of stock above which an item counts as a slow mover on Stock health (issue #414). Saved at once, like
// the credit rule: it changes a report, not what anyone may do, and the server audits the old and new values.

export const SLOW_MONTHS = [1, 2, 3, 4, 6, 9, 12, 18, 24].map((n) => ({ value: n, label: n === 1 ? '1 month' : `${n} months` }));

export function SlowMoversSetting({ read, onSaved }: { read: CapabilitiesRead; onSaved: () => void }) {
  const queryClient = useQueryClient();
  const current = read.capabilities.slow_mover_months ?? 4;
  const save = useMutation({
    mutationFn: (months: number) => retail.changeCapabilities({ slow_mover_months: months }, read.version),
    onSuccess: (next) => {
      queryClient.setQueryData(['retail', 'capabilities'], next);
      void queryClient.invalidateQueries({ queryKey: ['retail', 'stock-health'] });
      onSaved();
    },
  });
  const options = SLOW_MONTHS.some((o) => o.value === current) ? SLOW_MONTHS : [...SLOW_MONTHS, { value: current, label: `${current} months` }].sort((a, b) => a.value - b.value);
  return (
    <section className="stack" aria-label="Slow stock" data-testid="slow-movers-setting">
      <h2>Slow stock</h2>
      <Problem error={save.error && new Error(changeFailureText(save.error, queryClient))} />
      <Choice id="slow-months" label="Stock that would take longer than this to sell is slow" value={current} options={options} onChange={(m) => save.mutate(m)} />
      <p className="hint">Stock health lists these items under Slow movers, at the selling pace of the last 90 days.</p>
    </section>
  );
}
