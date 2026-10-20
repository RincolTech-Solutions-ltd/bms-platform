import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { businessToday, retail, type CapabilitiesRead } from '../../../api/retail';
import { Problem, changeFailureText } from './ui';

// How credit sales count (ADR-043, issue #340): when the sale is made (today's way, the default) or when the buyer pays.
// A money setting, so a choice is read back in plain words and confirmed before it is saved. The server audits the old and
// the new value; nothing recorded is changed, only how the reports, the home figures and the daily savings count.

export type CreditRecognition = 'on_sale' | 'on_payment';

export const RECOGNITION_CHOICES: readonly { value: CreditRecognition; label: string; hint: string }[] = [
  {
    value: 'on_sale',
    label: 'When the sale is made',
    hint: 'A credit sale counts as a sale and as profit on the day it is made, before the buyer has paid. This is how it works today.',
  },
  {
    value: 'on_payment',
    label: 'When the buyer pays',
    hint: 'A credit sale is listed as On credit and gives no profit until the buyer pays. Each payment counts on the day it is received, with its share of the profit.',
  },
];

/** The saved choice; anything but on_payment (including a server that does not send it) is today's way. */
export const recognitionOf = (c: CapabilitiesRead['capabilities'] | undefined): CreditRecognition =>
  c?.credit_sale_recognition === 'on_payment' ? 'on_payment' : 'on_sale';

/**
 * What choosing a value will change, in plain words, one sentence each. Empty when nothing changes. The change counts from `day`
 * (today's business date): credit sales dated before it keep counting the way they did, so no profit is counted twice and no
 * savings already saved move.
 */
export function recognitionSentences(from: CreditRecognition, to: CreditRecognition, day: string = businessToday()): string[] {
  if (from === to) return [];
  if (to === 'on_payment') {
    return [
      `From ${day} on, a credit sale stays out of Received money, Profit and the daily savings until the buyer pays. It shows as On credit.`,
      'When the buyer pays, that money counts on the day it is entered, with its share of the sale profit. A part payment gives part of the profit.',
      `Credit sales dated before ${day} keep counting the way they did: their profit stays on the day of the sale, and payments on them add nothing.`,
      'Stock still goes down when the sale is made. Your accounts do not change. Savings already saved are not changed.',
    ];
  }
  return [
    `From ${day} on, a credit sale counts as a sale, as profit and in the daily savings on the day it is made, even before the buyer pays.`,
    `Credit sales dated before ${day} keep counting the way they did: those counted when the buyer pays still count as the money comes in.`,
  ];
}

export function CreditRecognitionSetting({ read, onSaved }: { read: CapabilitiesRead; onSaved?: () => void }) {
  const queryClient = useQueryClient();
  const saved = recognitionOf(read.capabilities);
  const [choice, setChoice] = useState<CreditRecognition>(saved);
  const [reviewing, setReviewing] = useState(false);
  const save = useMutation({
    mutationFn: () => retail.changeCapabilities({ credit_sale_recognition: choice }, read.version),
    onSuccess: (next) => {
      queryClient.setQueryData(['retail', 'capabilities'], next);
      // Every figure that depends on the rule is read again.
      void queryClient.invalidateQueries({ queryKey: ['retail', 'dashboard'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'profit'] });
      setReviewing(false);
      onSaved?.();
    },
    onError: () => setReviewing(false),
  });
  const sentences = recognitionSentences(saved, choice);
  const since = read.capabilities.credit_recognition_effective_from;
  return (
    <fieldset className="stack" data-tour="retail-credit-recognition">
      <legend>When does a credit sale count as income and profit?</legend>
      {since && <p className="hint" data-testid="credit-since">This rule has stood since {since}. Credit sales made before then count the way they did.</p>}
      {RECOGNITION_CHOICES.map((c) => (
        <label key={c.value}>
          <input
            type="radio"
            name="credit-recognition"
            checked={choice === c.value}
            disabled={save.isPending}
            onChange={() => { setChoice(c.value); setReviewing(false); }}
          />
          <span>
            <strong>{c.label}</strong>
            <span className="hint"> {c.hint}</span>
          </span>
        </label>
      ))}
      <Problem error={save.error && new Error(changeFailureText(save.error, queryClient))} />
      {reviewing ? (
        <section className="rt-card" aria-label="Check your change">
          <h3>Check your change</h3>
          <ul>
            {sentences.map((line) => <li key={line}>{line}</li>)}
          </ul>
          <p className="hint">It is recorded with your name. Nothing already recorded is deleted or changed.</p>
          <p className="cluster">
            <button type="button" className="rt-primary" disabled={save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving' : 'Confirm'}</button>
            <button type="button" className="btn-ghost" disabled={save.isPending} onClick={() => setReviewing(false)}>Back</button>
          </p>
        </section>
      ) : (
        <p className="cluster">
          <button type="button" className="rt-primary" disabled={sentences.length === 0} onClick={() => setReviewing(true)}>Review change</button>
          {choice !== saved && <button type="button" className="btn-ghost" onClick={() => setChoice(saved)}>Undo</button>}
        </p>
      )}
    </fieldset>
  );
}
