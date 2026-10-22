import { useEffect, useRef } from 'react';

export default function LoginNotice({ onConfirm, onCancel }) {
  const dialog = useRef(null);
  const confirm = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    dialog.current.showModal();
    confirm.current.focus();
    return () => { previous?.focus(); };
  }, []);
  return <dialog className="login-notice" ref={dialog} aria-labelledby="login-notice-title" aria-describedby="login-notice-description"
    onCancel={(event) => { event.preventDefault(); onCancel(); }}>
    <h2 id="login-notice-title">로그인이 필요해요</h2>
    <p id="login-notice-description">AI 향수 추천 기능을 이용하려면 로그인이 필요합니다.</p>
    <div className="login-notice-actions"><button type="button" className="button button-outline" onClick={onCancel}>닫기</button>
      <button type="button" className="button button-dark" ref={confirm} onClick={onConfirm}>확인</button></div>
  </dialog>;
}
