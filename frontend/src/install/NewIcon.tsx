import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { BRANDING_QUERY, brandOf, useTenantHost } from '../app/branding';
import { IosSteps } from './InstallPrompt';
import { isIosDevice, standalone } from './install';

// A new logo on the home screen (issue #453, ADR-066). Android updates an installed app's icon by itself; an iPhone keeps the
// icon it took at Add to Home Screen until the app is removed and added again. Two pieces say so:
//   NewIconNote: on the set-up screen, after a logo is saved, for the admin who changed it.
//   NewLogoCard: in the staff area of the installed app on an iPhone or iPad, once per new icon version. This device remembers the
//     version it last saw; localStorage is per origin and every business has its own host, so the key is per business. The first
//     run only remembers the version, since the icon was just added and is current.

export const ICON_VERSION_KEY = 'bms.install.iconVersion';

/** The version this device last saw, null when none, undefined when storage cannot be read (then nothing is shown). */
function readSeen(): string | null | undefined {
  try {
    return localStorage.getItem(ICON_VERSION_KEY);
  } catch {
    return undefined;
  }
}

function writeSeen(version: string) {
  try {
    localStorage.setItem(ICON_VERSION_KEY, version);
  } catch {
    // Private mode and blocked storage both throw: the card cannot be remembered, so it is simply not shown.
  }
}

/** The note beside the logo after a save, with the iPhone steps one tap away. */
export function NewIconNote() {
  const [showSteps, setShowSteps] = useState(false);
  return (
    <div className="stack" data-testid="new-icon-note">
      <p role="status">
        Your logo is now your app's icon. Android phones update it by themselves, usually within a day. On an iPhone, remove BMS from the
        home screen and add it again.
      </p>
      {showSteps ? (
        <IosSteps />
      ) : (
        <p>
          <button type="button" className="btn-ghost" onClick={() => setShowSteps(true)}>
            Show me how on an iPhone
          </button>
        </p>
      )}
    </div>
  );
}

/** The business's icon version once its branding has loaded ('' with no logo), null while unknown or on a host with no business. */
function useIconVersion(): string | null {
  const tenantHost = useTenantHost() === true;
  const branding = useQuery({ ...BRANDING_QUERY, enabled: tenantHost });
  if (!tenantHost || !branding.data) return null;
  return brandOf(branding.data, null).iconVersion ?? '';
}

export function NewLogoCard() {
  return <NewLogoNotice version={useIconVersion()} />;
}

/** The card for a known version; shown only in the installed app on an iPhone or iPad. */
export function NewLogoNotice({ version }: { version: string | null }) {
  const installedIos = isIosDevice() && standalone();
  const [seen, setSeen] = useState(readSeen);

  useEffect(() => {
    if (!installedIos || version === null) return;
    // The first run, or the logo removed: remember the version without a card (there is no new logo to see).
    if (seen === null || (version === '' && seen !== undefined && seen !== version)) {
      writeSeen(version);
      setSeen(version);
    }
  }, [installedIos, version, seen]);

  if (!installedIos || version === null || version === '' || seen === null || seen === undefined || seen === version) return null;

  function done() {
    if (version === null) return;
    writeSeen(version);
    setSeen(version);
  }

  return (
    <aside className="card stack" aria-label="New logo" data-testid="new-logo-card">
      <h2>Your business has a new logo</h2>
      <p>
        To see it on your home screen: press and hold BMS, tap <strong>Remove App</strong>, then open the link in Safari and add it again.
      </p>
      <IosSteps />
      <p>
        <button type="button" className="btn-primary" onClick={done}>
          Done
        </button>
      </p>
    </aside>
  );
}
