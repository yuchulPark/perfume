import { createRecommendationJob, getRecommendationJob, cancelRecommendationJob, recommendationFailure } from '../api/recommendations.js';

export const JOB_HANDLE_KEY = 'scent-archive.recommendation-job';
export const JOB_POLL_INTERVAL_MS = 2000;
export const MAX_JOB_POLLS = 450;
const safeStorage = () => { try { return globalThis.sessionStorage; } catch { return undefined; } };
function readHandle(storage) {
  try {
    const data = JSON.parse(storage?.getItem(JOB_HANDLE_KEY) || 'null');
    if (data && /^[0-9a-f]{64}$/.test(data.token) && (data.id == null || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(data.id))) return { token: data.token, id: data.id || null };
    storage?.removeItem(JOB_HANDLE_KEY);
  } catch { /* Storage may be blocked. The current mounted page still retains its handle. */ }
  return null;
}
function newToken() { return [...crypto.getRandomValues(new Uint8Array(32))].map((value) => value.toString(16).padStart(2, '0')).join(''); }

/** Bounded polling; failures stop until an explicit user action. Only the opaque handle is persisted. */
export class RecommendationJobSession {
  constructor({ storage = safeStorage(), create = createRecommendationJob, get = getRecommendationJob, cancel = cancelRecommendationJob, token = newToken } = {}) {
    this.storage = storage; this.api = { create, get, cancel }; this.token = token;
    this.handle = readHandle(storage); this.listeners = new Set(); this.sequence = 0; this.polls = 0;
    this.state = { status: this.handle ? 'unknown' : 'idle', canCancel: !!this.handle?.id };
  }
  subscribe = (listener) => { this.listeners.add(listener); return () => this.listeners.delete(listener); };
  getSnapshot = () => this.state;
  update(values) { this.state = { ...values, canCancel: !!this.handle }; this.listeners.forEach((listener) => listener()); }
  save() {
    try { if (this.handle) this.storage?.setItem(JOB_HANDLE_KEY, JSON.stringify(this.handle)); else this.storage?.removeItem(JOB_HANDLE_KEY); } catch { /* No question or result is stored as a fallback. */ }
  }
  mount() { this.mounted = true; if (this.handle) this.resume(); }
  unmount() {
    this.mounted = false; this.sequence++; clearTimeout(this.timer); this.http?.abort();
    // Navigation does not pretend to stop server work. A same-tab reload resumes with the opaque handle.
  }
  async request(kind, operation) {
    clearTimeout(this.timer); this.http?.abort();
    const sequence = ++this.sequence; const http = new AbortController(); this.http = http;
    try {
      const view = await operation(http.signal);
      if (!this.mounted || sequence !== this.sequence) return;
      this.apply(view);
    } catch (error) {
      if (!this.mounted || sequence !== this.sequence || http.signal.aborted) return;
      if (error.kind === 'auth') {
        this.cancelRequested = false;
        if (kind === 'create') { this.handle = null; this.save(); }
        this.update({ status: 'unauthenticated', error });
      } else if (error.kind === 'lost' || (kind === 'create' && (error.kind === 'forbidden' || ['INVALID_REQUEST', 'RECOMMEND_BUSY', 'RECOMMEND_DISABLED'].includes(error.code)))) {
        this.cancelRequested = false;
        this.handle = null; this.save(); this.update({ status: error.kind === 'lost' ? 'lost' : 'error', error });
      } else {
        this.cancelRequested = false;
        this.update({ status: 'unknown', error });
      }
    }
  }
  apply(view) {
    this.handle = { ...this.handle, id: view.jobId }; this.save();
    if (['SUCCEEDED', 'CANCELLED', 'FAILED', 'TIMED_OUT'].includes(view.state)) {
      this.handle = null; this.save(); this.cancelRequested = false;
      if (view.state === 'SUCCEEDED') this.update({ status: 'success', data: view.result });
      else if (view.state === 'CANCELLED') this.update({ status: 'cancelled' });
      else this.update({ status: 'error', error: recommendationFailure(view.errorStatus, view.errorCode) });
      return;
    }
    if (this.cancelRequested && view.state === 'RUNNING') { this.cancelRequested = false; this.cancel(); return; }
    this.cancelRequested = false;
    this.update({ status: view.state === 'CANCELLING' ? 'cancelling' : 'loading', confirmed: true });
    if (++this.polls >= MAX_JOB_POLLS) { this.update({ status: 'unknown', exhausted: true }); return; }
    this.timer = setTimeout(() => this.poll(), JOB_POLL_INTERVAL_MS);
  }
  start(query, topK) {
    if (this.handle) return;
    this.cancelRequested = false;
    this.handle = { token: this.token(), id: null }; this.save(); this.polls = 0;
    this.update({ status: 'loading' });
    const handle = this.handle;
    return this.request('create', (signal) => this.api.create({ query, topK }, handle.token, { signal }));
  }
  poll() {
    if (!this.handle || !this.mounted) return;
    const handle = this.handle;
    return this.request('get', (signal) => this.api.get(handle.id, handle.token, { signal }));
  }
  resume() {
    if (!this.handle) return;
    this.polls = 0; this.update({ status: 'loading', recovering: true }); return this.poll();
  }
  cancel() {
    if (!this.handle) return;
    this.update({ status: 'cancelling', confirmed: false });
    if (!this.handle.id) { this.cancelRequested = true; return; }
    this.cancelRequested = false;
    const handle = this.handle;
    return this.request('cancel', (signal) => this.api.cancel(handle.id, handle.token, { signal }));
  }
}
