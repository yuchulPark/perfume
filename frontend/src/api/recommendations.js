import { ApiError } from './client.js';
import { csrfHeaders } from './auth.js';

export const MAX_QUERY_LENGTH = 500;
export const DEFAULT_TOP_K = 5;
export const MAX_TOP_K = 20;
// Backend request-timeout-ms defaults to 420000. Nginx waits 450s; leave time for its response.
export const RECOMMENDATION_TIMEOUT_MS = 480_000;

const messages = {
  auth: ['로그인이 필요해요', 'AI 향수 추천 기능을 이용하려면 로그인이 필요합니다.'],
  forbidden: ['요청 권한을 확인해 주세요', '요청이 허용되지 않았어요. 로그인 상태와 보안 토큰을 확인하려면 새로고침해 주세요.'],
  invalid: ['질문과 검색 개수를 확인해 주세요', '질문은 공백을 제외한 내용이 있어야 하고 500자 이내여야 해요. 검색 개수는 1~20 사이의 정수로 선택해 주세요.'],
  disabled: ['AI 추천이 비활성화되어 있어요', '현재 이 서비스에서는 AI 추천을 제공하지 않아요. 향수 컬렉션을 둘러보실 수 있어요.'],
  busy: ['다른 추천 요청을 처리하고 있어요', '동시 요청 한도를 초과해 요청이 접수되지 않았어요. 잠시 후 추천받기 버튼으로 다시 요청해 주세요.'],
  timeout: ['추천 응답 시간이 초과됐어요', '정해진 시간 안에 응답을 받지 못했어요. 서버 작업이 아직 진행 중일 수 있으니 잠시 후 다시 요청해 주세요.'],
  evidence: ['사용할 수 있는 추천 근거가 없어요', '저장된 추천 근거를 확인할 수 없어요. 잠시 후 다시 요청하거나 컬렉션을 둘러보세요.'],
  unavailable: ['추천 서비스를 잠시 이용할 수 없어요', '추천에 필요한 서비스가 준비되지 않았거나 처리가 중단됐어요. 잠시 후 다시 요청해 주세요.'],
  server: ['추천을 완료하지 못했어요', '서버에서 추천 답변을 만들지 못했어요. 잠시 후 다시 요청해 주세요.'],
  response: ['추천 응답을 확인할 수 없어요', '올바른 형식의 추천 결과를 받지 못했어요. 잠시 후 다시 요청해 주세요.'],
  network: ['서버에 연결할 수 없어요', '네트워크 연결을 확인한 뒤 다시 요청해 주세요.'],
  lost: ['작업 정보를 찾을 수 없어요', '서버가 재시작되었거나 작업 정보가 만료됐을 수 있어요. 이전 결과를 복구할 수 없어요.'],
};

// Only these exact status/code pairs are trusted. Never display provider detail/title or arbitrary codes.
const knownCodes = new Map([
  ['INVALID_REQUEST', [400, 'invalid']], ['RECOMMEND_BUSY', [429, 'busy']],
  ['RECOMMEND_JOB_NOT_FOUND', [404, 'lost']],
  ['RECOMMEND_DISABLED', [503, 'disabled']], ['RECOMMEND_INTERRUPTED', [503, 'unavailable']],
  ['MODEL_MISMATCH', [503, 'unavailable']], ['LLM_UNAVAILABLE', [503, 'unavailable']],
  ['DATABASE_UNAVAILABLE', [503, 'unavailable']], ['EVIDENCE_UNAVAILABLE', [503, 'evidence']],
  ...['RECOMMEND_TIMEOUT', 'SEARCH_TIMEOUT', 'LLM_TIMEOUT', 'DATABASE_TIMEOUT'].map((code) => [code, [504, 'timeout']]),
  ...['SEARCH_FAILED', 'LLM_HTTP_ERROR', 'LLM_INVALID_RESPONSE', 'GENERATION_FAILED'].map((code) => [code, [502, 'server']]),
]);

export class RecommendationError extends ApiError {
  constructor(status, kind, code = null) {
    const [title, message] = messages[kind];
    super(status, message);
    this.name = 'RecommendationError';
    this.title = title;
    this.kind = kind;
    this.code = code;
  }
}

export function recommendationFailure(status, code) {
  if (status === 401) return new RecommendationError(status, 'auth');
  if (status === 403) return new RecommendationError(status, 'forbidden');
  const known = knownCodes.get(code);
  const trusted = known?.[0] === status;
  const fallback = { 400: 'invalid', 429: 'busy', 502: 'server', 503: 'unavailable', 504: 'timeout' };
  return new RecommendationError(status, trusted ? known[1] : fallback[status] || 'server', trusted ? code : null);
}

const JOBS_PATH = '/api/ai/perfumes/recommend/jobs';
export const JOB_HTTP_TIMEOUT_MS = 15_000;
const jobStates = new Set(['RUNNING', 'CANCELLING', 'CANCELLED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT']);
const validId = (id) => typeof id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id);
export function validateJobResponse(data) {
  if (!data || !validId(data.jobId) || !jobStates.has(data.state)
    || (data.state !== 'SUCCEEDED' && data.result != null)) throw new RecommendationError(200, 'response');
  if (data.state === 'SUCCEEDED') validateResponse(data.result);
  if (['FAILED', 'TIMED_OUT'].includes(data.state) && (!Number.isInteger(data.errorStatus) || typeof data.errorCode !== 'string')) throw new RecommendationError(200, 'response');
  return data;
}

async function jobRequest(path, method, token, body, { signal, fetcher = globalThis.fetch } = {}) {
  if (typeof token !== 'string' || !/^[0-9a-f]{64}$/.test(token)) throw new RecommendationError(400, 'invalid');
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; abort(); }, JOB_HTTP_TIMEOUT_MS);
  const check = () => {
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');
    if (timedOut) throw new RecommendationError(0, 'timeout');
  };
  try {
    check();
    const response = await fetcher(path, { method, signal: controller.signal, cache: 'no-store', credentials: 'same-origin',
      headers: { Accept: 'application/json, application/problem+json', 'X-Recommendation-Token': token, ...(method === 'POST' ? csrfHeaders() : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    let data;
    try { data = await response.json(); } catch { /* Safe status fallback for proxy errors. */ }
    check();
    if (!response.ok) throw recommendationFailure(response.status, data?.code);
    return validateJobResponse(data);
  } catch (error) {
    check();
    if (error instanceof RecommendationError) throw error;
    throw new RecommendationError(0, 'network');
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}

export function createRecommendationJob({ query, topK = DEFAULT_TOP_K }, token, options) {
  if (validateRecommendationInput(query, topK)) return Promise.reject(new RecommendationError(400, 'invalid', 'INVALID_REQUEST'));
  return jobRequest(JOBS_PATH, 'POST', token, { query, topK }, options);
}
export function getRecommendationJob(id, token, options) {
  if (id != null && !validId(id)) return Promise.reject(new RecommendationError(400, 'response'));
  return jobRequest(id ? `${JOBS_PATH}/${id}` : JOBS_PATH, 'GET', token, null, options).then((data) => {
    if (id && data.jobId !== id) throw new RecommendationError(200, 'response');
    return data;
  });
}
export function cancelRecommendationJob(id, token, options) {
  if (!validId(id)) return Promise.reject(new RecommendationError(400, 'response'));
  return jobRequest(`${JOBS_PATH}/${id}/cancel`, 'POST', token, null, options).then((data) => {
    if (data.jobId !== id) throw new RecommendationError(200, 'response');
    return data;
  });
}

export function validateRecommendationInput(query, topK = DEFAULT_TOP_K) {
  // Java String.length() and HTML maxLength both count UTF-16 units. Match Java whitespace/space chars.
  const blank = typeof query !== 'string' || /^[\p{Z}\u0009-\u000d\u001c-\u001f]*$/u.test(query);
  return blank || query.length > MAX_QUERY_LENGTH || !Number.isInteger(topK) || topK < 1 || topK > MAX_TOP_K
    ? messages.invalid[1] : '';
}

function validateResponse(data) {
  if (!data || typeof data.answer !== 'string' || !data.answer.trim() || !Array.isArray(data.sources)
    || data.sources.length > MAX_TOP_K || data.returnedCount !== data.sources.length
    || !data.sources.every((source) => source && Number.isSafeInteger(source.perfumeId) && source.perfumeId > 0
      && typeof source.name === 'string' && source.name.trim() && typeof source.brandName === 'string'
      && Number.isFinite(source.similarity))) {
    throw new RecommendationError(200, 'response');
  }
  return data;
}

export async function recommendPerfumes({ query, topK = DEFAULT_TOP_K }, { signal, fetcher = globalThis.fetch } = {}) {
  if (validateRecommendationInput(query, topK)) throw new RecommendationError(400, 'invalid', 'INVALID_REQUEST');
  const controller = new AbortController();
  const abort = () => controller.abort();
  let timedOut = false;
  const checkAbort = () => {
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');
    if (timedOut) throw new RecommendationError(0, 'timeout');
  };
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; abort(); }, RECOMMENDATION_TIMEOUT_MS);
  try {
    checkAbort();
    const response = await fetcher('/api/ai/perfumes/recommend', {
      method: 'POST', signal: controller.signal, credentials: 'same-origin',
      headers: { Accept: 'application/json, application/problem+json', 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify({ query, topK }),
    });
    checkAbort();
    if (!response.ok) {
      let body;
      try { body = await response.json(); } catch { /* Proxies may return HTML or an empty body. */ }
      checkAbort();
      throw recommendationFailure(response.status, body?.code);
    }
    let data;
    try { data = await response.json(); } catch { throw new RecommendationError(response.status, 'response'); }
    checkAbort();
    return validateResponse(data);
  } catch (error) {
    checkAbort();
    if (error instanceof RecommendationError) throw error;
    throw new RecommendationError(0, 'network');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}
