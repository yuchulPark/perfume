const numberFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 3 });
export const text = (value) => typeof value === 'string' && value.trim() && !/^(nan|null|undefined)$/i.test(value.trim()) ? value.trim() : '';
export const items = (value) => Array.isArray(value) ? value.filter(Boolean) : [];
export const numeric = (value) => typeof value === 'number' && Number.isFinite(value);
export const formatNumber = (value, fallback = '정보 없음') => numeric(value) ? numberFormatter.format(value) : fallback;
export const formatScore = (value) => formatNumber(value);

const labels = {
  masculine: '남성적인', feminine: '여성적인', unisex: '남녀 공용', genderless: '성별 구분 없음',
  highly_rated: '매우 좋은 평가', liked: '좋은 평가', beloved: '높은 호감도',
  long_lasting: '오래 지속되는', very_long_lasting: '매우 오래 지속되는', short_lasting: '짧게 지속되는',
  strong: '강한', very_strong: '매우 강한', noticeable: '뚜렷한', subtle: '은은한', soft: '부드러운',
  moderate: '보통', medium: '보통', versatile: '폭넓은', very_suitable: '매우 적합', suitable: '적합',
  fair: '무난한 가치', great: '좋은 가치', poor: '낮은 평가',
  spring: '봄', summer: '여름', fall: '가을', autumn: '가을', winter: '겨울',
  day: '낮', night: '밤', daytime: '낮', nighttime: '밤', high: '높음', low: '낮음',
};
export const categoryLabel = (value) => labels[text(value)] || text(value);
export const hasMetric = (metric) => !!metric && (numeric(metric.score) || !!text(metric.category) || numeric(metric.nRecords));

export function findMetric(metrics, ...keys) {
  return keys.map((key) => metrics?.[key]).find(hasMetric) ?? null;
}
export function metricFamily(metrics, prefix) {
  return Object.entries(metrics ?? {}).filter(([key, value]) => (key === prefix || key.startsWith(`${prefix}.`) || key.startsWith(`${prefix}[`)) && hasMetric(value));
}
export function readFilters(searchParams) {
  const parsed = Number(searchParams.get('page') ?? '0');
  return { q: searchParams.get('q')?.trim() ?? '', brandSlug: searchParams.get('brandSlug') ?? '',
    page: Number.isSafeInteger(parsed) && parsed >= 0 && parsed <= Math.floor(2147483647 / 20) ? parsed : 0, size: 20 };
}
export function normalizeId(id) {
  if (typeof id !== 'string' || !/^\d+$/.test(id)) return null;
  const parsed = BigInt(id);
  return parsed > 0n && parsed <= 9223372036854775807n ? parsed.toString() : null;
}
export function paginationPages(page, totalPages) {
  const start = Math.max(0, Math.min(page - 2, totalPages - 5));
  return Array.from({ length: Math.min(5, Math.max(0, totalPages)) }, (_, index) => start + index);
}
export function safeImageUrl(value) {
  const candidate = text(value);
  if (!candidate) return '';
  try {
    const url = new URL(candidate, 'http://localhost');
    return ['http:', 'https:'].includes(url.protocol) ? candidate : '';
  } catch { return ''; }
}
