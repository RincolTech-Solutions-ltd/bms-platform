import { useRef, useState } from 'react';
import type { RoleAssignmentRequest, StaffBranch, StaffUser } from '../../../api/users';
import { RolePick } from './role-pick';
import { TENANT_ADMIN, assignmentFor, type RoleChoice } from './state';

// Change what a person may do: their roles, each with a branch unless it is the administrator (PUT /users/{id}/roles replaces the
// whole list). The last active administrator cannot be changed away; the server refuses it and the words arrive here.

interface Row {
  roleKey: string;
  branchId: string;
}

export function RoleEditor({ user, roles, branches, onSave, onCancel }: {
  user: StaffUser;
  roles: RoleChoice[];
  branches: StaffBranch[];
  onSave: (roles: RoleAssignmentRequest[]) => Promise<string | null>;
  onCancel: () => void;
}) {
  const [rows, setRows] = useState<Row[]>(() => (user.roles ?? []).map((r) => ({ roleKey: r.role_key ?? '', branchId: r.branch_id ?? '' })));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const saving = useRef(false);
  const ready = rows.length > 0 && rows.every((r) => r.roleKey !== '');

  async function save() {
    if (!ready || saving.current) return;
    saving.current = true;
    setBusy(true);
    setError(null);
    const failure = await onSave(rows.map((r) => assignmentFor(r.roleKey, r.branchId)));
    saving.current = false;
    setBusy(false);
    if (failure) setError(failure);
  }

  return (
    <form
      className="rt-card form-stack"
      aria-label={`Change the roles of ${user.full_name ?? 'this person'}`}
      onSubmit={(e) => {
        e.preventDefault();
        void save();
      }}
    >
      <h3>Roles of {user.full_name}</h3>
      {rows.map((row, i) => (
        <div key={i} className="stack">
          <RolePick
            roles={roles}
            branches={branches}
            roleKey={row.roleKey}
            branchId={row.branchId}
            label={rows.length > 1 ? `Role ${i + 1}` : 'Role'}
            disabled={busy}
            onChange={(next) => setRows((all) => all.map((r, j) => (j === i ? next : r)))}
          />
          {rows.length > 1 && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy} onClick={() => setRows((all) => all.filter((_, j) => j !== i))}>
              Remove this role
            </button>
          )}
        </div>
      ))}
      {!rows.some((r) => r.roleKey === TENANT_ADMIN) && (
        <p>
          <button type="button" className="btn-ghost btn-sm" disabled={busy} onClick={() => setRows((all) => [...all, { roleKey: '', branchId: '' }])}>
            Add another role
          </button>
        </p>
      )}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      <p className="cluster">
        <button type="submit" className="rt-primary" disabled={!ready || busy}>{busy ? 'Saving' : 'Save the roles'}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}
