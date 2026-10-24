import { csrfHeaders, refreshCsrf } from '../api/auth.js';

export class SupportError extends Error {
  constructor(status, code = 'SUPPORT_UNAVAILABLE') { super(code); this.status = status; this.code = code; }
}
// randomUUID is unavailable on the existing LAN HTTP origin; getRandomValues works there.
export function newClientMessageId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
export async function supportRequest(path, { method = 'GET', body, signal } = {}) {
  if (method !== 'GET') await refreshCsrf();
  let response;
  try {
    response = await fetch(path, { method, signal: signal || AbortSignal.timeout(15_000), credentials: 'same-origin', cache: 'no-store',
      headers: { Accept: 'application/json', ...(method !== 'GET' ? csrfHeaders() : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
  } catch { throw new SupportError(0); }
  let data; try { data = await response.json(); } catch { throw new SupportError(response.status); }
  if (!response.ok) throw new SupportError(response.status, /^SUPPORT_[A-Z_]+$/.test(data?.code) ? data.code : undefined);
  return data;
}
export async function recoverMessages(id, admin, after = 0) {
  const base = admin ? '/api/admin/support/conversations' : '/api/support/conversations';
  let messages = [], page;
  for (let count = 0; count < 101; count++) {
    page = await supportRequest(`${base}/${encodeURIComponent(id)}/messages?after=${after}&limit=100`);
    messages.push(...page.messages);
    if (!page.hasMore) return { conversation: page.conversation, messages };
    if (page.nextAfter <= after) throw new SupportError(0);
    after = page.nextAfter;
  }
  throw new SupportError(0);
}
export function mergeMessages(previous, incoming) {
  const unique = new Map(previous.map(message => [message.id, message]));
  incoming.forEach(message => unique.set(message.id, message));
  return [...unique.values()].sort((a, b) => a.sequence - b.sequence || a.id.localeCompare(b.id));
}
export function supportErrorText(error) {
  const texts = {
    SUPPORT_CLOSED: '종료된 상담에는 메시지를 보낼 수 없어요.',
    SUPPORT_INVALID_MESSAGE: '공백을 제외한 메시지를 2,000자 이내로 입력해 주세요.',
    SUPPORT_RATE_LIMIT: '메시지를 너무 빠르게 보내고 있어요. 잠시 후 다시 시도해 주세요.',
    SUPPORT_MESSAGE_LIMIT: '이 상담의 메시지 한도에 도달했어요.',
    SUPPORT_NOT_FOUND: '이 세션에서 상담을 찾을 수 없어요. 세션이 만료되면 이전 대화를 복구할 수 없어요.',
  };
  return texts[error?.code] || '처리 결과를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.';
}
