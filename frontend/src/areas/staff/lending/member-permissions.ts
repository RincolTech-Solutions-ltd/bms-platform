import type { Me } from '../../../api/client';
import { branchesWhere } from '../retail/permissions';

// Which member and collateral screens and actions a session is offered (issue #280). The API is the
// authority (it checks the permission and the branch on every call); this only decides what the menu and
// the pages show, from the /me permissions and modules. Each name is the permission its route declares
// (docs/sdd/08-security-design.md, chapter 7 sections 7.11.11 and 7.11.14).

export const MEMBERS_READ = 'lending.members.read';
export const MEMBERS_CREATE = 'lending.members.create';
export const MEMBERS_UPDATE = 'lending.members.update';
export const MEMBERS_VERIFY = 'lending.members.verify_kyc';
export const COLLATERAL_READ = 'lending.collateral.read';
export const COLLATERAL_MANAGE = 'lending.collateral.manage';
export const COLLATERAL_RELEASE = 'lending.collateral.release_request';

type Session = Pick<Me, 'permissions'> & { modules?: string[] };

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];
const hasLending = (me: Session): boolean => !me.modules || me.modules.includes('lending');

/** The Members menu entry: the user may read members, and the tenant has lending when /me says. */
export const showMembers = (me: Session): boolean => hasLending(me) && held(me).includes(MEMBERS_READ);
/** The Collateral menu entry. */
export const showCollateral = (me: Session): boolean => hasLending(me) && held(me).includes(COLLATERAL_READ);

export const mayCreateMember = (me: Session): boolean => showMembers(me) && held(me).includes(MEMBERS_CREATE);
export const mayEditMember = (me: Session): boolean => showMembers(me) && held(me).includes(MEMBERS_UPDATE);
export const mayVerifyKyc = (me: Session): boolean => showMembers(me) && held(me).includes(MEMBERS_VERIFY);
export const mayManageCollateral = (me: Session): boolean => showCollateral(me) && held(me).includes(COLLATERAL_MANAGE);
export const mayReleaseCollateral = (me: Session): boolean => showCollateral(me) && held(me).includes(COLLATERAL_RELEASE);

/** The branches a new member may be added to: those where the user holds the create permission. */
export function memberBranches<B extends { id?: string }>(me: Pick<Me, 'permission_scopes'> & { branches?: B[] }): B[] {
  return branchesWhere(me, MEMBERS_CREATE);
}
