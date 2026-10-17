import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { FormError, unknownOutcome } from '../../../api/lending-forms';
import { useMembersApi } from '../../../api/lending-context';
import type { DuplicateCandidate, MemberRecord } from '../../../api/members';
import { branchLabel } from '../../../auth/branch';
import { useStaff } from '../context';
import { newIdempotencyKey } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { Group, SaveBar, SelectField, TextField, useFormErrors } from './form-fields';
import { localDate } from './loan-state';
import { MembersGate } from './member-ui';
import { mayCreateMember, mayEditMember, memberBranches } from './member-permissions';
import {
  blankMember, createBody, draftOf, duplicateHold, GENDERS, ID_TYPES, isEmpty, MARITAL, memberProblems, normaliseNin, normaliseUganda, reasonWords,
  updateBody, type MemberDraft,
} from './member-state';
import { PhotoQueueControl, usePhotoQueue, type Sender } from './photo-queue';

// Create and edit a member (issue #280, FR-MEM-01 to FR-MEM-04, FR-MEM-09, FR-MEM-10). One column, big inputs, a sticky save
// bar. Before anything is saved the screen asks the server for likely duplicates by phone, national ID and name and shows
// them (masked), and holds the save for the same national ID, or for the same phone until the person ticks that this is
// someone else. An edit sends the member's version as If-Match; a stale version keeps what was typed and says so.
// One Idempotency-Key per form instance, stable across retries. After an answer that may have been lost the screen looks
// the member up before it lets the person save again, so a retry cannot register someone twice.

const ORDER = ['branch_id', 'full_name', 'phone', 'alt_phone', 'national_id', 'other_id_number', 'date_of_birth', 'monthly_income_minor'] as const;
const STATUSES = [
  { value: 'active', label: 'Active' },
  { value: 'inactive', label: 'Not active for now' },
  { value: 'exited', label: 'Left the business' },
];

/** The likely duplicates of what is typed, asked after a short pause and only once the entries are worth asking about. */
function useDuplicates(draft: MemberDraft, selfId?: string): { candidates: DuplicateCandidate[]; checking: boolean } {
  const api = useMembersApi();
  const phone = normaliseUganda(draft.phone) ?? undefined;
  const nin = draft.idType === 'nin' ? (normaliseNin(draft.nationalId) ?? undefined) : undefined;
  const name = draft.fullName.trim().length >= 4 ? draft.fullName.trim() : undefined;
  const [settled, setSettled] = useState({ phone, nin, name });
  useEffect(() => {
    const t = setTimeout(() => setSettled({ phone, nin, name }), 400);
    return () => clearTimeout(t);
  }, [phone, nin, name]);
  const ask = settled.phone !== undefined || settled.nin !== undefined || settled.name !== undefined;
  const found = useQuery({
    queryKey: ['members', 'duplicates', settled.phone ?? '', settled.nin ?? '', settled.name ?? ''],
    queryFn: ({ signal }) => api.duplicateCheck({ full_name: settled.name, phone: settled.phone, national_id: settled.nin }, signal),
    enabled: ask,
    staleTime: 30_000,
    retry: false,
  });
  const candidates = (ask ? (found.data ?? []) : []).filter((c) => c.id !== selfId);
  return { candidates, checking: ask && found.isFetching };
}

export function DuplicateList({ candidates, hold, confirmed, onConfirm }: {
  candidates: readonly DuplicateCandidate[];
  hold: 'nin' | 'phone' | null;
  confirmed: boolean;
  onConfirm: (yes: boolean) => void;
}) {
  if (candidates.length === 0) return null;
  const ninHeld = hold === 'nin';
  const phoneOnly = candidates.some((c) => (c.match_reasons ?? []).includes('phone'));
  return (
    <section className="alert alert-warning mf-dups" role="status" aria-labelledby="mf-dups-title" data-tour="member-duplicates">
      <h2 id="mf-dups-title">Someone like this is already registered</h2>
      <ul className="ln-list">
        {candidates.map((c) => (
          <li key={c.id ?? c.member_no}>
            <strong>{c.member_no}</strong>
            {c.full_name ? `, ${c.full_name}` : ''} (matches on {reasonWords(c.match_reasons)})
            {c.phone_e164_masked ? <span className="hint"> Phone {c.phone_e164_masked}</span> : null}
            {c.id && c.in_scope ? <> <Link to="/staff/lending/members/$memberId" params={{ memberId: c.id }} target="_blank" rel="noreferrer">Open this member</Link></> : null}
          </li>
        ))}
      </ul>
      {ninHeld && <p>This national ID number is already registered, so this member cannot be saved. Check the number, or open the member above.</p>}
      {phoneOnly && !ninHeld && (
        <label>
          <input type="checkbox" checked={confirmed} onChange={(e) => onConfirm(e.target.checked)} />
          This is a different person who shares the phone number
        </label>
      )}
    </section>
  );
}

export function MemberForm({ member, onSaved }: { member?: MemberRecord; onSaved?: (m: MemberRecord) => void }) {
  const { me, branch } = useStaff();
  const api = useMembersApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const editing = member !== undefined;
  const allowed = memberBranches(me);
  const startBranch = allowed.find((b) => b.id === branch)?.id ?? (allowed.length === 1 ? allowed[0]?.id : undefined) ?? '';
  const [draft, setDraft] = useState<MemberDraft>(() => (member ? draftOf(member) : blankMember(startBranch)));
  const [status, setStatus] = useState(member?.status ?? 'active');
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [maybeSaved, setMaybeSaved] = useState<DuplicateCandidate | null>(null);
  const [saved, setSaved] = useState<MemberRecord | null>(null);
  const idFront = usePhotoQueue();
  const idBack = usePhotoQueue();
  const original = useMemo(() => (member ? draftOf(member) : undefined), [member]);
  const problems = memberProblems(draft, localDate(), editing, original);
  const form = useFormErrors(problems, 'mf');
  const { candidates, checking } = useDuplicates(draft, member?.id);
  const hold = duplicateHold(candidates, draft.confirmedNotDuplicate);
  const set = <K extends keyof MemberDraft>(key: K, value: MemberDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  const sendPhotos = async (id: string): Promise<boolean> => {
    const send = (kind: 'id_front' | 'id_back'): Sender<unknown> => (p, o) => api.uploadDocument(id, kind, p.blob, p.fileName, o);
    const a = await idFront.sendAll(send('id_front'));
    const b = await idBack.sendAll(send('id_back'));
    return a.failed + b.failed === 0;
  };

  const finish = (m: MemberRecord) => {
    void queryClient.invalidateQueries({ queryKey: ['members'] });
    if (onSaved) onSaved(m);
    else void navigate({ to: '/staff/lending/members/$memberId', params: { memberId: m.id ?? '' } });
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setFailure(null);
    if (!form.attempt(ORDER)) return;
    if (hold) return;
    setBusy(true);
    try {
      if (member) {
        const body = updateBody(member, draft);
        if (status !== member.status) body.status = status as 'active';
        if (isEmpty(body) && !body.status) {
          finish(member);
          return;
        }
        finish(await api.update(member.id ?? '', member.version ?? 0, body));
        return;
      }
      const created = await api.create(createBody(draft), idempotencyKey);
      setSaved(created);
      if (idFront.waiting + idBack.waiting === 0 || (await sendPhotos(created.id ?? ''))) finish(created);
    } catch (error) {
      if (error instanceof FormError) {
        form.refuse(error.fields, ORDER);
        if (error.code === 'version_conflict') setStale(true);
        setFailure(Object.keys(error.fields).length > 0 && error.code !== 'version_conflict' ? null : error.message);
        // The answer may have been lost: look the member up before another save is allowed.
        if (!editing && unknownOutcome(error)) {
          const phone = normaliseUganda(draft.phone);
          const found = phone ? await api.duplicateCheck({ phone }).catch(() => []) : [];
          const same = found.find((c) => (c.match_reasons ?? []).includes('phone') && c.full_name?.trim().toLowerCase() === draft.fullName.trim().toLowerCase());
          if (same) setMaybeSaved(same);
        }
      } else setFailure('Something went wrong. Try again.');
    } finally {
      setBusy(false);
    }
  }

  const branchName = branchLabel((me.branches ?? []).find((b) => b.id === draft.branchId));
  const blocked = hold === 'nin' ? 'This national ID is already registered.' : hold === 'phone' ? 'Tick the box above to say this is a different person.' : maybeSaved ? 'This member looks already saved.' : undefined;
  const photoSender: Sender<unknown> | undefined = saved ? (p, o) => api.uploadDocument(saved.id ?? '', 'id_front', p.blob, p.fileName, o) : undefined;

  if (saved) {
    return (
      <section className="rt-card stack" aria-live="polite">
        <h2>{saved.full_name} is saved</h2>
        <p>Member number {saved.member_no}. A photo did not go through. Send it again, or open the member and add it later.</p>
        <PhotoQueueControl id="mf-front-retry" label="Photo of the front of the ID" queue={idFront} sender={photoSender} />
        <Link to="/staff/lending/members/$memberId" params={{ memberId: saved.id ?? '' }} className="btn btn-block rt-primary">Open the member</Link>
      </section>
    );
  }

  return (
    <form className="mf" onSubmit={(e) => void submit(e)} noValidate aria-label={editing ? 'Change member details' : 'Add a member'} data-tour="member-form">
      {form.count(ORDER) > 0 && (
        <p role="alert" className="alert alert-danger">Some entries need a look. They are marked below.</p>
      )}
      {stale && (
        <div role="alert" className="alert alert-warning">
          <p>Someone else changed this member since you opened it. What you typed is kept.</p>
          <button type="button" className="btn-sm" onClick={() => void queryClient.invalidateQueries({ queryKey: ['members', 'one', member?.id] })}>Reload their version</button>
        </div>
      )}
      {maybeSaved && (
        <div role="alert" className="alert alert-warning">
          <p>The answer did not come back, and a member with this name and phone is already saved.</p>
          <Link to="/staff/lending/members/$memberId" params={{ memberId: maybeSaved.id ?? '' }}>Open {maybeSaved.member_no}</Link>
        </div>
      )}
      <Group title="Who is the member" tourId="member-who">
        {editing ? (
          <p className="field-hint">Branch: {branchName}. To move a member to another branch, ask an administrator.</p>
        ) : (
          <SelectField name="branch_id" label="Branch" form={form} required value={draft.branchId} onChange={(v) => set('branchId', v)}
            options={allowed.map((b) => ({ value: b.id ?? '', label: branchLabel(b) }))} blankLabel="Choose a branch" />
        )}
        <TextField name="full_name" label="Full name" form={form} required value={draft.fullName} onChange={(v) => set('fullName', v)} autoComplete="off" maxLength={200} />
        <TextField name="phone" label="Phone number" form={form} required type="tel" inputMode="tel" value={draft.phone} onChange={(v) => set('phone', v)}
          hint="For example 0712 345 678. A +256 number works too." />
        <TextField name="alt_phone" label="Another phone number (if any)" form={form} type="tel" inputMode="tel" value={draft.altPhone} onChange={(v) => set('altPhone', v)} />
      </Group>
      <Group title="ID" tourId="member-id">
        <SelectField name="id_type" label="Kind of ID" form={form} value={draft.idType} onChange={(v) => set('idType', v)} options={ID_TYPES} />
        {draft.idType === 'nin' && (
          <TextField name="national_id" label="National ID number (NIN)" form={form} required value={draft.nationalId} onChange={(v) => set('nationalId', v)}
            hint="From the card. It starts with CM or CF." />
        )}
        {draft.idType !== 'nin' && draft.idType !== 'none' && (
          <TextField name="other_id_number" label="ID number" form={form} required value={draft.otherIdNumber} onChange={(v) => set('otherIdNumber', v)} maxLength={40} />
        )}
      </Group>
      <DuplicateList candidates={candidates} hold={hold} confirmed={draft.confirmedNotDuplicate} onConfirm={(yes) => set('confirmedNotDuplicate', yes)} />
      {checking && candidates.length === 0 && <p className="field-hint" role="status">Checking whether this member is already registered</p>}
      <Group title="About the member">
        <TextField name="date_of_birth" label="Date of birth" form={form} type="date" value={draft.dateOfBirth} onChange={(v) => set('dateOfBirth', v)} max={localDate()} />
        <SelectField name="gender" label="Gender" form={form} value={draft.gender} onChange={(v) => set('gender', v)} options={GENDERS} />
        <SelectField name="marital_status" label="Marital status" form={form} value={draft.maritalStatus} onChange={(v) => set('maritalStatus', v)} options={MARITAL} />
        <TextField name="occupation" label="Work or business" form={form} value={draft.occupation} onChange={(v) => set('occupation', v)} maxLength={120} />
        <TextField name="monthly_income_minor" label="Usual income a month (UGX)" form={form} inputMode="numeric" value={draft.monthlyIncome} onChange={(v) => set('monthlyIncome', v)}
          hint={editing && original?.monthlyIncome !== '' ? 'A saved income can be changed but not removed. Type 0 for none.' : undefined} />
      </Group>
      <Group title="Where the member lives">
        <TextField name="district" label="District" form={form} value={draft.district} onChange={(v) => set('district', v)} maxLength={100} />
        <TextField name="village" label="Village or town" form={form} value={draft.village} onChange={(v) => set('village', v)} maxLength={100} />
        <TextField name="location" label="Street or landmark" form={form} value={draft.location} onChange={(v) => set('location', v)} maxLength={200} />
      </Group>
      {!editing && (
        <Group title="Photo of the ID" tourId="member-id-photo">
          <PhotoQueueControl id="mf-front" label="Front of the ID" queue={idFront} hint="Take it flat and in good light. It is shrunk before it is sent." />
          <PhotoQueueControl id="mf-back" label="Back of the ID (optional)" queue={idBack} />
          <p className="field-hint">You can also add the photo later from the member's page. Without it, the details cannot be checked.</p>
        </Group>
      )}
      {editing && (
        <Group title="Status">
          <SelectField name="status" label="Member status" form={form} value={status} onChange={setStatus} options={STATUSES}
            hint="A member with an open loan or account cannot be set to left the business." />
        </Group>
      )}
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label={editing ? 'Save changes' : 'Save member'} busyLabel="Saving" busy={busy} blocked={blocked}
        cancel={<Link to={editing ? '/staff/lending/members/$memberId' : '/staff/lending/members'} params={editing ? { memberId: member?.id ?? '' } : undefined as never} className="btn btn-ghost">Cancel</Link>}
        tourId="member-save" />
    </form>
  );
}

function NewMember() {
  const { me } = useStaff();
  return (
    <MembersGate title="Add a member" tourId="member-new">
      {mayCreateMember(me) ? <MemberForm /> : <p className="empty-state">You do not have access to add members.</p>}
    </MembersGate>
  );
}

const editRoute = getRouteApi('/staff/lending/members/$memberId/edit');

function EditMember() {
  const { memberId } = editRoute.useParams();
  const { me } = useStaff();
  const api = useMembersApi();
  const member = useQuery({ queryKey: ['members', 'one', memberId], queryFn: () => api.get(memberId) });
  return (
    <MembersGate title="Change member details" tourId="member-edit">
      {member.isPending && <p className="loading">Loading the member</p>}
      <Problem error={member.error} />
      {member.data && (mayEditMember(me, member.data.branch_id) ? <MemberForm key={member.data.version} member={member.data} /> : <p className="empty-state">You do not have access to change this member.</p>)}
    </MembersGate>
  );
}

export const NewRoute = createLazyRoute('/staff/lending/members/new')({ component: NewMember });
export const EditRoute = createLazyRoute('/staff/lending/members/$memberId/edit')({ component: EditMember });
