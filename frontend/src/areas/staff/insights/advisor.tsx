import { useQuery } from '@tanstack/react-query';
import { advisor, type AdvisorBrief } from '../../../api/advisor';

// The AI assistant card of the Insights page (extras slice C3, issue #226; ADR-037; spec 5.2): the brief, the time it was made,
// chips for the figures it rests on (each opens the rows behind that metric), and the advisory line. Every text on it carries
// the server's label: "AI assistant (advisory)" for a brief written by our code from a model's structured answer, "Automatic
// summary" for the facts-only brief that stands in whenever the model was not used, for any reason. The text is never the
// model's own words; the card only shows what the server sends, as text.

const WHY: Record<string, string | undefined> = {
  off: 'The AI assistant is switched off for your business.',
  scope: 'The AI brief covers every branch. This summary is for the branches you can see.',
  validation: 'The assistant gave no usable answer today.',
  provider_down: 'The assistant could not be reached today.',
  cap: 'The assistant reached its daily limit.',
  not_ready: 'Today\'s AI brief is not ready yet.',
  unavailable: 'The assistant is not available right now.',
};

export function asOfText(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

/** The brief as it is drawn; apart from the query so it can be rendered with a fixed brief. */
export function AdvisorBriefView({ brief, onChip }: { brief: AdvisorBrief; onChip?: (metric: string) => void }) {
  const automatic = brief.source !== 'model';
  const why = brief.reason ? WHY[brief.reason] : undefined;
  return (
    <section className="iv-card iv-advisor" aria-label={brief.label} data-tour="advisor-card">
      <p className="iv-card-label">
        <span className={automatic ? 'badge' : 'badge badge-info'}>{brief.label}</span>
        {brief.as_of && <span className="ln-muted"> As of {asOfText(brief.as_of)}</span>}
      </p>
      <p>{brief.summary}</p>
      {brief.points.length > 0 && (
        <ul>
          {brief.points.map((p, i) => (
            <li key={i}>{p.text}</li>
          ))}
        </ul>
      )}
      {brief.facts.length > 0 && (
        <p className="cluster" aria-label="Figures used">
          {brief.facts.map((f) =>
            f.metric && onChip ? (
              <button key={f.key} type="button" className="btn-ghost btn-sm" onClick={() => onChip(f.metric as string)}>
                {f.label}: {f.display}
              </button>
            ) : (
              <span key={f.key} className="badge">
                {f.label}: {f.display}
              </span>
            ),
          )}
        </p>
      )}
      {why && automatic && <p className="ln-muted">{why}</p>}
      <p className="ln-muted">{brief.note}</p>
    </section>
  );
}

/** The card: loads the brief and never lets a failure break the page (a failed load shows one quiet line). */
export function AdvisorCard({ onChip }: { onChip?: (metric: string) => void }) {
  const query = useQuery({ queryKey: ['advisor', 'brief'], queryFn: advisor.brief, staleTime: 60_000 });
  if (query.data) return <AdvisorBriefView brief={query.data} onChip={onChip} />;
  return (
    <section className="iv-card iv-advisor" aria-label="Automatic summary" data-tour="advisor-card">
      <p className="iv-card-label">
        <span className="badge">Automatic summary</span>
      </p>
      <p className="ln-muted">{query.isPending ? 'Loading the summary' : 'The summary is not available right now. The figures below are.'}</p>
    </section>
  );
}
