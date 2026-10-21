import { useId } from 'react';
import type { StaffBranch } from '../../../api/users';
import { TENANT_ADMIN, branchUnavailable, type RoleChoice } from './state';

// One role and, unless it is the administrator, the shops and stores it applies in (FR-IAM-01, ADR-017, ADR-055). The administrator
// works in every shop and store, so no branch is asked for; any other role is ticked for the branches the person works in, or for
// every shop and store. The server scopes everything the role does to the ticked branches.

export function RolePick({ roles, branches, roleKey, branchIds, onChange, disabled, label = 'What will this person do?', problem }: {
  roles: RoleChoice[];
  branches: StaffBranch[];
  roleKey: string;
  /** Empty means every shop and store. */
  branchIds: string[];
  onChange: (next: { roleKey: string; branchIds: string[] }) => void;
  disabled?: boolean;
  label?: string;
  problem?: string;
}) {
  const id = useId();
  const chosen = roles.find((r) => r.key === roleKey);
  const open = branches.filter((b) => b.id && (b.status ?? 'active') === 'active');
  // A branch the person has that is closed now (or gone from the list): shown ticked, so it can be taken off before saving.
  const gone = branchIds.filter((b) => branchUnavailable(b, branches));
  const every = branchIds.length === 0;
  const toggle = (branch: string, on: boolean) =>
    onChange({ roleKey, branchIds: on ? [...branchIds, branch] : branchIds.filter((b) => b !== branch) });
  const name = (b: StaffBranch) => `${b.name ?? b.code}${b.code && b.name ? ` (${b.code})` : ''}${b.sells_goods === false ? ', a store' : ''}`;
  return (
    <div className="stack">
      <label htmlFor={`${id}-role`}>{label}</label>
      <select id={`${id}-role`} value={roleKey} disabled={disabled} aria-invalid={Boolean(problem)} onChange={(e) => onChange({ roleKey: e.target.value, branchIds: e.target.value === TENANT_ADMIN ? [] : branchIds })}>
        <option value="">Choose a role</option>
        {roles.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
      </select>
      {problem && <p role="alert" className="field-error">{problem}</p>}
      {chosen && <p className="hint">{chosen.about}</p>}
      {chosen && chosen.key !== TENANT_ADMIN && (
        <fieldset className="stack">
          <legend>Where does this person work?</legend>
          <p className="hint">Tick the shops and stores. They see and do this only there.</p>
          <label className="check">
            <input type="checkbox" checked={every} disabled={disabled} onChange={(e) => { if (e.target.checked) onChange({ roleKey, branchIds: [] }); }} />
            Every shop and store
          </label>
          {open.map((b) => (
            <label key={b.id} className="check">
              <input type="checkbox" checked={branchIds.includes(b.id ?? '')} disabled={disabled} onChange={(e) => toggle(b.id ?? '', e.target.checked)} />
              {name(b)}
            </label>
          ))}
          {gone.map((g) => {
            const closed = branches.find((b) => b.id === g);
            return (
              <label key={g} className="check">
                <input type="checkbox" checked disabled={disabled} onChange={() => toggle(g, false)} />
                {closed ? `${closed.name ?? closed.code} (closed)` : 'A branch that is closed'}
              </label>
            );
          })}
          {gone.length > 0 && <p role="alert" className="field-error">This branch is closed. Take it off, and tick another branch or every shop and store, before you save.</p>}
        </fieldset>
      )}
      {chosen?.key === TENANT_ADMIN && <p className="hint">An administrator works in every shop and store and sees everything, profit and costs included.</p>}
    </div>
  );
}
