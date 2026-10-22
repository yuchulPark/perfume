import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { safeReturnPath } from '../api/auth.js';
import { Icon } from '../components/Icon.jsx';

export default function LoginPage() {
  const auth = useAuth(), location = useLocation(), navigate = useNavigate();
  const [visible, setVisible] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const submitting = useRef(false);
  const returnTo = safeReturnPath(location.state?.from);
  useEffect(() => { document.title = '로그인 · 향의 기록'; }, []);
  if (auth.phase === 'ready' && auth.user) return <Navigate to={returnTo} replace />;

  async function submit(event) {
    event.preventDefault();
    if (submitting.current) return;
    const form = event.currentTarget;
    const username = form.elements.username.value.trim();
    const passwordField = form.elements.password;
    if (!username || !passwordField.value) { setError('아이디와 비밀번호를 입력해 주세요.'); return; }
    submitting.current = true; setBusy(true); setError('');
    try { await auth.signIn(username, passwordField.value); navigate(returnTo, { replace: true }); }
    catch (failure) { setError(failure.message); }
    finally { passwordField.value = ''; submitting.current = false; setBusy(false); }
  }
  return <div className="container auth-page"><section className="auth-card" aria-labelledby="login-title">
    <span className="eyebrow"><Icon name="leaf" size={16} />SCENT ARCHIVE</span>
    <h1 id="login-title">다시 만나 반가워요</h1><p className="auth-intro">로그인하고 나만의 향을 찾아보세요.</p>
    {auth.phase === 'ready' && !auth.enabled ? <p role="status">로그인 기능은 아직 준비 중이에요.</p> : <form onSubmit={submit} noValidate aria-label="로그인" aria-busy={busy}>
      <label htmlFor="login-username">아이디</label>
      <input id="login-username" name="username" autoComplete="username" maxLength={64} required disabled={busy} />
      <label htmlFor="login-password">비밀번호</label>
      <div className="auth-password"><input id="login-password" name="password" type={visible ? 'text' : 'password'} autoComplete="current-password" required disabled={busy} />
        <button type="button" aria-label={visible ? '비밀번호 숨기기' : '비밀번호 표시'} aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? '숨기기' : '표시'}</button></div>
      {error && <p className="recommend-validation" role="alert">{error}</p>}
      <button className="button button-dark auth-submit" disabled={busy || auth.phase === 'loading'}>{busy ? '로그인 중…' : '로그인'}</button>
      <p className="auth-help">운영자 전용 계정으로 로그인할 수 있어요. 최초 계정은 승인된 관리 도구에서 등록해 주세요.</p>
    </form>}
    <Link className="auth-back" to="/">향수 컬렉션 둘러보기 <Icon name="arrow" size={15} /></Link>
  </section></div>;
}
