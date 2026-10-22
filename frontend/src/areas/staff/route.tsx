import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Outlet, createLazyRoute, useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { api, fetchMe, fetchSettings, type Me } from '../../api/client';
import { loadRetailMock, retailMockEnabled } from '../../api/retail';
import { getAccessToken, refreshSession, setAccessToken, subscribe } from '../../auth/session';
import { InstallPrompt } from '../../install/InstallPrompt';
import { createPush } from '../../push/push';
import { TurnOnCard } from '../../push/TurnOnCard';
import { HelpMenu, TourProvider } from '../../tour/host';
import { CrumbProvider, StaffBreadcrumbs } from '../../components/breadcrumbs';
import { icons } from '../../components/icons';
import { AreaTabs, type MenuLink, SideMenu, useIsPhone } from '../../components/side-menu';
import { BrandLoader } from '../../components/states';
import { BranchPicker, useActiveBranch } from './branch-picker';
import { StaffContext } from './context';
import { canConfigureAdvisor, showInsights } from './insights/permissions';
import { showInvestments } from './lending/investment-state';
import { showCollateral, showMembers } from './lending/member-permissions';
import { showLending } from './lending/permissions';
import { showSavings } from './lending/savings-permissions';
import { showReports } from './reports/permissions';
import { RetailMenuGroup, RetailNav } from './retail/nav';
import { canSeeProfit, showRetail } from './retail/permissions';
import { showReceivePayments } from './payments/state';
import { showSms } from './sms/state';
import { showUsers } from './users/state';
import { rememberPage } from './feedback/feedback-state';
import { TwoStepNotices } from './two-step/notices';
import { OwnTwoStep } from './two-step/own';
import { mustSetUp } from './two-step/state';

// The staff area layout: restores the session from the refresh cookie, shows who is signed in,
// and holds the branch switcher (FR-BR-03). Switching the branch changes what lists show without
// signing out or changing the URL. What a user cannot do is hidden using /me; the API decides.

function useAccessToken() {
  return useSyncExternalStore(subscribe, getAccessToken);
}

// This device's notifications (ADR-050): registered again under whoever is signed in, and dropped on sign-out.
const push = createPush();

// A tenant admin who has not dismissed the set-up checklist lands on it once per page load.
let setupShown = false;

function StaffLayout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useAccessToken();
  // VITE_RETAIL_MOCK=1: a fake session so the retail screens run without a backend.
  const mock = retailMockEnabled();
  const [restoring, setRestoring] = useState(token === null && !mock);

  useEffect(() => {
    if (token !== null || mock) return;
    void refreshSession().then((ok) => {
      setRestoring(false);
      if (!ok) void navigate({ to: '/sign-in' });
    });
  }, [token, navigate, mock]);

  const me = useQuery({ queryKey: ['me', token], queryFn: (): Promise<Me> => (mock ? loadRetailMock().then((m) => m.mockMe(import.meta.env.VITE_RETAIL_MOCK_ROLE)) : fetchMe()),
    enabled: token !== null || mock,
  });
  const { branch, choose, ready } = useActiveBranch(me.data);
  const signedIn = me.data !== undefined;
  useEffect(() => {
    if (signedIn && !mock) void push.sync();
  }, [signedIn, mock]);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const phone = useIsPhone();
  // Feedback sends the page the person came from (issue #447).
  useEffect(() => rememberPage(pathname), [pathname]);
  const managesSettings = (me.data?.permissions ?? []).includes('core.settings.manage');
  const settings = useQuery({ queryKey: ['settings'], queryFn: fetchSettings, enabled: managesSettings && !mock });

  useEffect(() => {
    const home = pathname === '/staff' || pathname === '/staff/';
    if (setupShown || !home || !settings.data || settings.data.setup_dismissed) return;
    setupShown = true;
    void navigate({ to: '/staff/setup', replace: true });
  }, [pathname, settings.data, navigate]);

  if (restoring || !me.data || !ready) {
    return me.isError ? <p role="alert" className="alert alert-danger">Could not load your profile.</p> : <BrandLoader />;
  }

  const profile = me.data;
  if (mock) void loadRetailMock().then((m) => m.setMockProfitAccess(canSeeProfit(profile)));
  async function signOut() {
    // This device stops hearing this person's notifications before the session ends (it needs the session to say so).
    if (!mock) await push.forget();
    await api.POST('/api/v1/auth/logout');
    setAccessToken(null);
    queryClient.clear();
    await navigate({ to: '/sign-in' });
  }

  const recoveryCodesLeft = profile.unused_recovery_codes ?? 0;

  return (
    <StaffContext.Provider value={{ me: profile, branch, chooseBranch: choose }}>
      <TourProvider me={profile} saveProgress={!mock}>
        <CrumbProvider>
        <StaffBar me={profile} branch={branch} onBranch={choose} onSignOut={() => void signOut()} />
        <InstallPrompt />
        {!mock && <TurnOnCard push={push} />}
        {profile.mfa_enabled && recoveryCodesLeft <= 2 && (
          <p role="status" className="alert alert-warning">
            You have {recoveryCodesLeft} recovery codes left. Ask an admin to reset your MFA if you run out.
          </p>
        )}
        <TwoStepNotices me={profile} pathname={pathname} />
        {!phone && pathname.startsWith('/staff/retail') && showRetail(profile) && <RetailNav me={profile} />}
        <StaffBreadcrumbs />
        {mustSetUp(profile) ? <OwnTwoStep blocking /> : <Outlet />}
        </CrumbProvider>
      </TourProvider>
    </StaffContext.Provider>
  );
}

/** The areas the session may open, in tab order; Retail carries its shortcuts as a group for the phone side menu. */
function staffAreas(me: Me): MenuLink[] {
  const permissions = me.permissions ?? [];
  const areas: (MenuLink | false)[] = [
    { to: '/staff', label: 'Home', icon: icons.home, exact: true },
    permissions.includes('core.approvals.read') && { to: '/staff/approvals', label: 'Approvals', icon: icons.approve, tour: 'nav-approvals' },
    showLending(me) && { to: '/staff/lending', label: 'Loans', icon: icons.calendar, exact: true, tour: 'nav-lending' },
    showMembers(me) && { to: '/staff/lending/members', label: 'Members', icon: icons.people, tour: 'nav-members' },
    !showMembers(me) && showCollateral(me) && { to: '/staff/lending/collateral', label: 'Collateral', icon: icons.lock },
    showSavings(me) && { to: '/staff/lending/savings', label: 'Savings', icon: icons.savings },
    showInvestments(me) && { to: '/staff/lending/investments', label: 'Investments', icon: icons.valuation },
    showInsights(me) && { to: '/staff/insights', label: 'Insights', icon: icons.salesAnalysis, tour: 'nav-insights' },
    canConfigureAdvisor(me) && { to: '/staff/assistant', label: 'AI assistant', icon: icons.evaluation },
    showReports(me) && { to: '/staff/reports', label: 'Reports', icon: icons.documents },
    showRetail(me) && { to: '/staff/retail', label: 'Retail', icon: icons.sale, tour: 'nav-retail', group: <RetailMenuGroup me={me} /> },
    showSms(me) && { to: '/staff/sms', label: 'SMS', icon: icons.phone },
    showReceivePayments(me) && { to: '/staff/payments', label: 'Receive payments', icon: icons.banking },
    showUsers(me) && { to: '/staff/users', label: 'Users', icon: icons.user, tour: 'nav-users' },
    { to: '/staff/notifications', label: 'Notifications', icon: icons.bell, tour: 'nav-notifications' },
    { to: '/staff/feedback', label: 'Feedback', icon: icons.chat, tour: 'nav-feedback' },
    permissions.includes('core.settings.manage') && { to: '/staff/setup', label: 'Business set-up', icon: icons.settings },
  ];
  return areas.filter((a): a is MenuLink => a !== false);
}

/**
 * Who is signed in, Help, the branch switcher, sign out and the area tabs; below 720px the tabs, the name's Sign out and the
 * retail bar give way to the side menu (issue #455). Carries the data-tour anchors the first-run tours light up (tour/tours.ts).
 */
export function StaffBar({ me, branch, onBranch, onSignOut }: { me: Me; branch: string | null; onBranch: (selection: string) => void; onSignOut: () => void }) {
  const phone = useIsPhone();
  const areas = staffAreas(me);
  return (
    <header className="staff-bar">
      <div className="staff-bar-top">
        {phone && <SideMenu label="Staff areas" tour="nav-areas" links={areas} tours={['retail-nav']} name={me.full_name} onSignOut={onSignOut} />}
        <span className="staff-user">
          <span className="avatar">{icons.user}</span>
          <span className="staff-user-name">{me.full_name}</span>
        </span>
        <HelpMenu feedback />
        <BranchPicker me={me} branch={branch} onChoose={onBranch} />
        {!phone && <button className="btn-ghost btn-sm" onClick={onSignOut}>Sign out</button>}
      </div>
      {!phone && <AreaTabs label="Staff areas" tour="nav-areas" links={areas} />}
    </header>
  );
}

export const Route = createLazyRoute('/staff')({ component: StaffLayout });
