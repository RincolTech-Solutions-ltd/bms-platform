import createClient, { type Middleware } from 'openapi-fetch';
import { getAccessToken, refreshSession, tenantHeaders } from '../auth/session';
import { APP_UPDATING } from './retail-errors';
import type { components, paths } from './schema';

// The typed API client. `schema.d.ts` is generated from docs/api/openapi.json (the committed
// contract snapshot) by `npm run gen:api`; hand-written request or response types are not
// allowed (ADR-009, chapter 7 section 7.3). CI fails if the generated file is stale.

export type Member = components['schemas']['Member'];
export type MemberListItem = components['schemas']['MemberListItem'];
export type MemberPage = components['schemas']['MemberPage'];
export type Me = components['schemas']['Me'];
export type Branding = components['schemas']['Branding'];
export type TenantSettings = components['schemas']['TenantSettings'];
export type SignInResponse = components['schemas']['SignInResponse'];
export type MfaEnrolment = components['schemas']['MfaEnrolment'];
export type Approval = components['schemas']['Approval'];
export type ApprovalPage = components['schemas']['ApprovalPage'];
export type Problem = { code?: string; detail?: string; errors?: { field: string; message: string }[] };

// Every request carries the in-memory access token (chapter 7 section 7.4.1). In local
// development only, the tenant also travels as X-Tenant because localhost carries no slug.
const auth: Middleware = {
  onRequest({ request }) {
    Object.entries(tenantHeaders()).forEach(([k, v]) => request.headers.set(k, v));
    const token = getAccessToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  // An expired access token is refreshed once and a read is retried; writes are not replayed.
  async onResponse({ request, response }) {
    const authPath = ['/api/v1/auth/', '/api/v1/platform/auth/', '/api/v1/platform/sign-up/'].some((p) => request.url.includes(p));
    if (response.status !== 401 || authPath || request.method !== 'GET') {
      return response;
    }
    if (!(await refreshSession())) return response;
    const retry = new Request(request);
    retry.headers.set('Authorization', `Bearer ${getAccessToken() ?? ''}`);
    return fetch(retry);
  },
};

/** How long a request with an Idempotency-Key waits before its one retry; tests shorten it. */
export const updatingRetry = { delayMs: 4000 };

// A 502, 503 or 504 that is not the API's own problem body is the proxy (Caddy or Cloudflare) answering while no
// API is up, and so is a dropped connection while the device says it is online. Nothing reached the API, so the
// answer is plain words in a problem body every screen already shows, and the form keeps what was typed. A request
// with an Idempotency-Key is safe to send again, so it is, once, after a few seconds: the server replays the first
// result if the first attempt did land. Offline, a dropped connection stays the TypeError the offline paths expect.
const isGap = (response: Response) =>
  [502, 503, 504].includes(response.status) && !(response.headers.get('content-type') ?? '').includes('json');
const online = () => typeof navigator === 'undefined' || navigator.onLine !== false;
const updating = (status: number) =>
  new Response(JSON.stringify({ code: 'app_updating', detail: APP_UPDATING }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

const replays = new WeakMap<Request, Request>();
async function retryOnce(request: Request, send: typeof fetch): Promise<Response | undefined> {
  const replay = replays.get(request);
  replays.delete(request);
  if (!replay) return undefined;
  await new Promise((resolve) => setTimeout(resolve, updatingRetry.delayMs));
  if (request.signal?.aborted) return undefined;
  try {
    return await send(replay);
  } catch {
    return undefined;
  }
}

const deployGap: Middleware = {
  onRequest({ request }) {
    // The body is spent by the first attempt, so the retry needs its own copy taken before it is sent.
    if (request.headers.has('Idempotency-Key')) replays.set(request, request.clone());
    return request;
  },
  async onResponse({ request, response, options }) {
    if (!isGap(response)) return response;
    const retried = await retryOnce(request, options.fetch);
    return retried && !isGap(retried) ? retried : updating(response.status);
  },
  async onError({ request, error, options }) {
    if (!online() || (error as { name?: string } | null)?.name === 'AbortError') return;
    const retried = await retryOnce(request, options.fetch);
    if (retried) return retried;
    return new TypeError(APP_UPDATING);
  },
};

export { APP_UPDATING };
export const api = createClient<paths>({ baseUrl: '/' });
api.use(auth);
api.use(deployGap);

/** The problem body of a failed call, for showing its detail (chapter 7 section 7.7). */
export function problemOf(error: unknown): Problem {
  if (error instanceof Error && error.message === APP_UPDATING) return { code: 'app_updating', detail: APP_UPDATING };
  return (error ?? {}) as Problem;
}

/** Liveness of the API (GET /healthz). Outside /api/v1, so not part of the typed contract. */
export async function fetchHealth(): Promise<'UP' | 'DOWN'> {
  try {
    const response = await fetch('/healthz');
    if (!response.ok) return 'DOWN';
    const body = (await response.json()) as { status?: string };
    return body.status === 'UP' ? 'UP' : 'DOWN';
  } catch {
    return 'DOWN';
  }
}

export async function fetchMe(): Promise<Me> {
  const { data, error } = await api.GET('/api/v1/me');
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load your profile');
  return data;
}

export async function listMembers(
  branchIds: string[] | undefined,
  limit = 50,
  q?: string,
  signal?: AbortSignal,
): Promise<MemberPage> {
  const { data, error } = await api.GET('/api/v1/lending/members', {
    params: { query: { limit, branch_id: branchIds, q: q || undefined } },
    signal,
  });
  if (error || !data) throw new Error('Could not load members');
  return data;
}

/** The pending requests in the branches given, only of `actionTypes` when given (the server filters). */
export async function listPendingApprovals(branchIds: string[] | undefined, actionTypes?: string[]): Promise<ApprovalPage> {
  const { data, error } = await api.GET('/api/v1/approvals', {
    params: { query: { status: ['pending'], branch_id: branchIds, action_type: actionTypes } },
  });
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load approvals');
  return data;
}

export async function fetchSettings(): Promise<TenantSettings> {
  const { data, error } = await api.GET('/api/v1/settings');
  if (error || !data) throw new Error(problemOf(error).detail ?? 'Could not load the settings');
  return data;
}
