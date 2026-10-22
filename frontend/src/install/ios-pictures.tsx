import type { ReactNode } from 'react';

// The pictures beside the iPhone install steps (issue #448): our own simple drawings of the part of Safari each step is about,
// not screenshots and not Apple artwork. Like src/components/illustrations.tsx they carry no colour of their own: the classes in
// src/app/ui/illustrations.css paint them from the tokens, so they follow the light and dark themes and the business's colour.
// Each one is an image with a label that repeats its step, for a screen reader; the words of the step stay beside it.

function Picture({ label, children }: { label: string; children: ReactNode }) {
  return (
    <svg className="ill ios-step-pic" viewBox="0 0 120 90" role="img" aria-label={label}>
      {children}
    </svg>
  );
}

/** Safari in a normal tab: the address bar at the foot of a light page, no Private label. */
export const safariTab = (
  <Picture label="Use Safari, in a normal tab, not a Private one.">
    <rect className="ill-surface" x="20" y="4" width="80" height="82" rx="10" />
    <path className="ill-line" d="M32 20h56M32 30h40M32 40h48" />
    <rect className="ill-tint" x="28" y="56" width="64" height="16" rx="8" />
    <path className="ill-accent-line" d="M36 64h4" />
    <text className="ill-text" x="46" y="67">
      bms
    </text>
  </Picture>
);

/** The bottom toolbar, Share (the square with an arrow) ringed, the address bar above and the ⋯ button beside it. */
export const shareButton = (
  <Picture label="Tap Share, the square with an arrow. If the buttons are hidden, tap the address bar. If you still do not see Share, tap the more button.">
    <rect className="ill-surface" x="4" y="10" width="112" height="70" rx="10" />
    <rect className="ill-tint" x="14" y="20" width="76" height="16" rx="8" />
    <text className="ill-text" x="24" y="31">
      bms
    </text>
    <text className="ill-text ill-text-strong" x="96" y="32">
      ⋯
    </text>
    <path className="ill-line" d="M18 60l-5 5 5 5M34 60l5 5-5 5M86 58h12v12H86z" />
    <circle className="ill-accent-ring" cx="60" cy="63" r="12" />
    <path className="ill-accent-line" d="M57 60h-3v10h12V60h-3M60 66V54M56 58l4-4 4 4" />
  </Picture>
);

/** The share sheet's list with Add to Home Screen picked out, and the View More row under it. */
export const addToHomeScreen = (
  <Picture label="Tap Add to Home Screen. If it is not in the list, scroll down, or tap View More and it is there.">
    <rect className="ill-surface" x="4" y="4" width="112" height="82" rx="10" />
    <path className="ill-line" d="M14 20h60" />
    <rect className="ill-tint" x="10" y="30" width="100" height="22" rx="6" />
    <rect className="ill-accent-ring" x="14" y="35" width="12" height="12" rx="3" />
    <path className="ill-accent-line" d="M20 38v6M17 41h6" />
    <text className="ill-text ill-text-strong" x="32" y="45">
      Add to Home Screen
    </text>
    <text className="ill-text" x="14" y="74">
      View More
    </text>
    <path className="ill-line" d="M100 66l4 4-4 4" />
  </Picture>
);

/** The Add button at the top right, above the new icon. */
export const addButton = (
  <Picture label="Tap Add, at the top right. Then open BMS from its new icon.">
    <rect className="ill-surface" x="4" y="4" width="112" height="82" rx="10" />
    <path className="ill-line" d="M12 16h22" />
    <rect className="ill-accent-ring" x="82" y="8" width="28" height="18" rx="6" />
    <text className="ill-text ill-text-accent" x="87" y="21">
      Add
    </text>
    <rect className="ill-primary" x="44" y="38" width="32" height="32" rx="8" />
    <path className="ill-line" d="M44 80h32" />
  </Picture>
);
