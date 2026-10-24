import { useState } from 'react';
export function SupportMessageInput({ disabled, onSend }) {
  const [text, setText] = useState('');
  function submit(event) {
    event.preventDefault();
    if (disabled || !text.trim() || text.length > 2000) return;
    onSend(text); setText('');
  }
  return <form className="support-input" onSubmit={submit}>
    <label className="sr-only" htmlFor="support-message-input">상담 메시지 입력</label>
    <textarea id="support-message-input" value={text} maxLength={2000} rows={2} disabled={disabled}
      placeholder="메시지를 입력해 주세요" onChange={event => setText(event.target.value)}
      onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) submit(event); }} />
    <div><span>{text.length} / 2,000</span><button className="button button-dark" disabled={disabled || !text.trim()} type="submit">전송</button></div>
  </form>;
}
