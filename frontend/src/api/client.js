export class ApiError extends Error {
  constructor(status, message) { super(message); this.name = 'ApiError'; this.status = status; }
}

export async function getJson(path, { signal, fetcher = globalThis.fetch } = {}) {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timeout = setTimeout(() => { timedOut = true; abort(); }, 15_000);
  try {
    const response = await fetcher(path, { signal: controller.signal, headers: { Accept: 'application/json' } });
    if (!response.ok) {
      const message = response.status === 404 ? '찾으시는 향수 정보가 없습니다.'
        : response.status === 400 ? '검색 조건을 확인해 주세요.'
          : '정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
      throw new ApiError(response.status, message);
    }
    try { return await response.json(); }
    catch { throw new ApiError(response.status, '응답을 확인할 수 없어요. 다시 시도해 주세요.'); }
  } catch (error) {
    if (signal?.aborted) throw error;
    if (error instanceof ApiError) throw error;
    throw new ApiError(0, timedOut ? '응답이 지연되고 있어요. 다시 시도해 주세요.' : '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.');
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', abort);
  }
}

export function catalogPath({ q = '', brandSlug = '', page = 0, size = 20 } = {}) {
  const keyword = q.trim();
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (keyword) params.set('q', keyword);
  if (brandSlug) params.set('brandSlug', brandSlug);
  return `/api/perfumes${keyword ? '/search' : ''}?${params}`;
}

export async function getBrands(options) {
  const data = await getJson('/api/brands', options);
  if (!Array.isArray(data)) throw new ApiError(200, '브랜드 목록을 확인할 수 없어요.');
  return data;
}
export async function getPerfumes(filters, options) {
  const data = await getJson(catalogPath(filters), options);
  if (!data || !Array.isArray(data.content) || !Number.isInteger(data.page)
    || !Number.isInteger(data.totalPages) || !Number.isFinite(data.totalElements)) {
    throw new ApiError(200, '향수 목록을 확인할 수 없어요.');
  }
  return data;
}
export async function getPerfume(id, options) {
  const data = await getJson(`/api/perfumes/${encodeURIComponent(id)}`, options);
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new ApiError(200, '향수 정보를 확인할 수 없어요.');
  return data;
}
