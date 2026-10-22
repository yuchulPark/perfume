let csrf = null;

export class AuthError extends Error {
  constructor(status, message) { super(message); this.name = 'AuthError'; this.status = status; }
}
async function authRequest(path, options = {}) {
  let response;
  try {
    response = await fetch(`/api/auth/${path}`, { credentials: 'same-origin', cache: 'no-store',
      signal: AbortSignal.timeout(15_000), ...options });
  } catch { throw new AuthError(0, '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.'); }
  if (!response.ok) throw new AuthError(response.status, response.status === 401
    ? '아이디 또는 비밀번호를 확인해 주세요.' : response.status === 403
      ? '요청을 확인할 수 없어요. 새로고침 후 다시 시도해 주세요.' : '인증 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.');
  try { return await response.json(); }
  catch { throw new AuthError(0, '인증 응답을 확인할 수 없어요.'); }
}
export function csrfHeaders() { return csrf ? { [csrf.headerName]: csrf.token } : {}; }
export async function refreshCsrf() {
  csrf = null;
  const data = await authRequest('csrf');
  if (data?.enabled === true && data.headerName === 'X-CSRF-TOKEN' && typeof data.token === 'string' && data.token.length > 0) {
    csrf = { headerName: data.headerName, token: data.token };
  } else if (data?.enabled !== false) throw new AuthError(0, '보안 토큰을 확인할 수 없어요.');
}
export async function getSession() {
  const data = await authRequest('me');
  if (typeof data?.enabled !== 'boolean' || !(data.user === null
    || (typeof data.user?.username === 'string' && ['ADMIN', 'USER'].includes(data.user.role))))
    throw new AuthError(0, '인증 응답을 확인할 수 없어요.');
  return data;
}
export async function login(username, password) {
  await refreshCsrf();
  try {
    await authRequest('login', { method: 'POST', headers: { ...csrfHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ username, password }) });
  } finally { csrf = null; }
  // Authentication rotates the session and invalidates the previous CSRF token.
  await refreshCsrf();
  return getSession();
}
export async function logout() {
  await refreshCsrf();
  await authRequest('logout', { method: 'POST', headers: csrfHeaders() });
  csrf = null;
  await refreshCsrf();
  return getSession();
}

// Router state only; neither credentials nor the recommendation question enter URLs.
export function safeReturnPath(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(value)
      || /^\/(login|signup)(?:[/?#]|$)/.test(value)) return '/recommend';
  return value;
}
