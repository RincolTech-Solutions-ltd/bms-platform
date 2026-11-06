import { api, problemOf } from './client';
import type { components } from './schema';

// The reconciliation of an imported run and the customer's sign-off (issue #134, spec sections 3.9 and 3.10, FR-IMP-08): the
// tenant admin reads what the import brought in against their own sheet and signs it. Typed by the generated schema.

type S = components['schemas'];

export type SignOffRun = S['ImportSignOffRun'];
export type Reconciliation = S['ImportReconciliation'];
export type ReconciliationSection = S['ImportReconciliationSection'];
export type ReconciliationCheck = S['ImportReconciliationCheck'];
export type ReconciliationItem = S['ImportReconciliationItem'];
export type SignOffRequest = S['ImportSignOffRequest'];

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const fields = problem.errors ?? [];
  throw new Error(fields.length > 0 ? fields.map((f) => f.message).join(' ') : (problem.detail ?? fallback));
}

export async function signOffRuns(): Promise<SignOffRun[]> {
  const { data, error } = await api.GET('/api/v1/imports/sign-off');
  if (error || !data) fail(error, 'Could not load the imports to sign off.');
  return data.items ?? [];
}

export async function reconciliationOf(runId: string): Promise<Reconciliation> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/reconciliation', { params: { path: { run_id: runId } } });
  if (error || !data) fail(error, 'Could not load the reconciliation of this import.');
  return data;
}

export async function signOff(runId: string, body: SignOffRequest): Promise<Reconciliation> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/sign-off', { params: { path: { run_id: runId } }, body });
  if (error || !data) fail(error, 'Could not sign. Nothing was signed.');
  return data;
}

/** Saves the reconciliation as the plain text that is signed, with the sign-off once signed, on this device. */
export async function downloadReconciliation(runId: string, version: number): Promise<void> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/reconciliation.txt', {
    params: { path: { run_id: runId } },
    parseAs: 'blob',
  });
  if (error || !data) fail(error, 'Could not download the reconciliation. Try again.');
  const url = URL.createObjectURL(data as Blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `import-reconciliation-${version}.txt`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked later: revoking right after the click can cancel the download in Safari and Firefox.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
