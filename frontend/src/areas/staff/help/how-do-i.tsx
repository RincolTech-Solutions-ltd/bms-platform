import { Link, useRouterState } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { NewDot } from '../../../app/ui/NewBadge';
import { icons } from '../../../components/icons';
import { articleForScreen } from '../../../help/corpus';
import { HELP_FEATURE } from '../menu';
import { useAskEnabled } from './ask';

// The small How do I? link of a screen (issue #598): drawn by the staff layout under the breadcrumbs when an article this person
// may read names the page among its screens, and opening that article. Nothing shows on a page with no article.

export function HowDoILink({ pathname, me }: { pathname: string; me: Me }) {
  const article = pathname.startsWith('/staff/help') ? undefined : articleForScreen(pathname, me);
  if (!article) return null;
  return (
    <p className="how-do-i">
      <Link to="/staff/help/$articleId" params={{ articleId: article.id }} aria-label={`How do I? ${article.title}`} data-tour="how-do-i">
        {icons.help}
        How do I?
      </Link>
    </p>
  );
}

/**
 * The Ask button of the staff top bar (issue #603, round 2), next to Search on every staff screen but /staff/help itself: Ask
 * while the business has Ask BMS on, else Help. It opens /staff/help#ask, where the cursor lands in the Ask BMS question box (the
 * article search while Ask BMS is off), and carries the help page's New dot until the person has opened it once. Below 380px only
 * the icon shows; its name stays Ask BMS (or Help).
 */
export function AskButton() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const ask = useAskEnabled();
  if (pathname.replace(/\/$/, '') === '/staff/help') return null;
  return (
    <Link to="/staff/help" hash="ask" className="btn btn-ghost btn-icon ask-open" aria-label={ask ? 'Ask BMS' : 'Help'}>
      {icons.ask}
      <span className="ask-open-word">{ask ? 'Ask' : 'Help'}</span>
      <NewDot feature={HELP_FEATURE} />
    </Link>
  );
}
