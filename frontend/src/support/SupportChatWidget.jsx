import { useState } from 'react';
import { useAuth } from '../auth/AuthContext.jsx';
import { useSupportEnabled } from './SupportContext.jsx';
import { SupportMessageList } from './SupportMessageList.jsx';
import { SupportMessageInput } from './SupportMessageInput.jsx';
import { useSupportConversation } from './useSupportConversation.js';

export const connectionLabels = { connected: '실시간 연결됨', connecting: '연결 중', reconnecting: '연결 끊김 · 자동 재연결 중', disconnected: '연결 대기', expired: '세션 만료' };
function SupportChatWindow({ onClose }) {
  const auth = useAuth();
  const chat = useSupportConversation({ enabled: auth.phase === 'ready' });
  return <section className="support-window" role="dialog" aria-label="향의 기록 고객센터">
    <header><div><span className="eyebrow">SCENT ARCHIVE · SUPPORT</span><h2>무엇을 도와드릴까요?</h2></div><button type="button" aria-label="고객센터 닫기" onClick={onClose}>×</button></header>
    <div className={`support-connection ${chat.status}`} role="status">{connectionLabels[chat.status]}</div>
    <p className="support-privacy">사람이 직접 답변해요. 비밀번호·연락처 등 민감정보는 남기지 마세요. 세션이 만료되면 이전 상담은 복구할 수 없어요.</p>
    {chat.error && <p className="support-error" role="alert">{chat.error}<button type="button" onClick={chat.restore}>대화 다시 확인</button></p>}
    {chat.conversation ? <>
      <SupportMessageList messages={chat.messages} pending={chat.pending} onRetry={message => chat.send(message.text, message)} />
      {chat.conversation.status === 'CLOSED' && <p className="support-closed" role="status">상담이 종료되었습니다. 대화는 이 세션에서 확인할 수 있어요.</p>}
      <SupportMessageInput disabled={chat.status !== 'connected' || chat.conversation.status === 'CLOSED'} onSend={chat.send} />
    </> : <div className="support-welcome"><p>향수와 사이트 이용에 대해<br />편하게 질문을 남겨 주세요.</p><button className="button button-dark" disabled={chat.starting || chat.status !== 'connected'} type="button" onClick={chat.start}>{chat.starting ? '상담 준비 중…' : '상담 시작하기'}</button></div>}
  </section>;
}
export default function SupportChatWidget() {
  const enabled = useSupportEnabled(), [open, setOpen] = useState(false);
  if (!enabled) return null;
  return <div className="support-widget">{open && <SupportChatWindow onClose={() => setOpen(false)} />}
    <button className="support-launcher" type="button" aria-expanded={open} onClick={() => setOpen(value => !value)}>{open ? '채팅창 접기' : '고객센터 채팅'}</button>
  </div>;
}
