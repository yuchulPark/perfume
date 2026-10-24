import { useEffect, useRef } from 'react';
export function SupportMessageList({ messages, pending, admin, onRetry }) {
  const end = useRef(null);
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }); }, [messages.length, pending.length]);
  return <div className="support-messages" role="log" aria-label="상담 메시지" aria-live="polite">
    {!messages.length && !pending.length && <p className="support-empty">궁금한 점을 남겨 주세요.<br />관리자가 확인 후 직접 답변해 드려요.</p>}
    {messages.map(message => <div key={message.id} className={`support-message ${message.senderType === (admin ? 'ADMIN' : 'VISITOR') ? 'own' : ''}`}>
      <span className="support-sender">{message.senderType === 'ADMIN' ? '상담 관리자' : '방문자'}</span>
      <p>{message.text}</p><time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</time>
    </div>)}
    {pending.map(message => <div key={message.clientMessageId} className="support-message own support-pending">
      <p>{message.text}</p><span>{message.failed ? '전송 결과 확인 필요' : '전송 중…'}</span>
      {message.failed && <button type="button" onClick={() => onRetry(message)}>같은 메시지 다시 확인·전송</button>}
    </div>)}<div ref={end} />
  </div>;
}
