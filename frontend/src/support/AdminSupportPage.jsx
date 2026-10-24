import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useSupportEnabled } from './SupportContext.jsx';
import { supportErrorText, supportRequest } from './api.js';
import { SupportMessageList } from './SupportMessageList.jsx';
import { SupportMessageInput } from './SupportMessageInput.jsx';
import { useSupportConversation } from './useSupportConversation.js';
import { connectionLabels } from './SupportChatWidget.jsx';

function AdminConsole() {
  const auth = useAuth(), navigate = useNavigate(), [rooms, setRooms] = useState([]), [selectedId, setSelectedId] = useState(null), [more, setMore] = useState(false), [error, setError] = useState('');
  const busy = useRef(false);
  function expired() { auth.expire(); navigate('/login', { state: { from: '/admin/support' } }); }
  const reload = useCallback(async event => {
    if (event?.conversation) {
      setRooms(old => [event.conversation, ...old.filter(room => room.id !== event.conversation.id)].sort((a,b) => b.updatedAt.localeCompare(a.updatedAt)));
      return;
    }
    if (busy.current) return; busy.current = true;
    try { const data = await supportRequest('/api/admin/support/conversations'); setRooms(data.conversations); setMore(data.hasMore); }
    catch (failure) { if ([401,403].includes(failure.status)) expired(); else setError(supportErrorText(failure)); }
    finally { busy.current = false; }
  }, []);
  const chat = useSupportConversation({ enabled: true, admin: true, selectedId, onRoomsChanged: reload, onExpired: expired });
  useEffect(() => { reload(); }, [reload]);
  async function close() {
    try { await supportRequest(`/api/admin/support/conversations/${selectedId}/close`, { method: 'POST' }); await chat.restore(); await reload(); }
    catch (failure) { if ([401,403].includes(failure.status)) expired(); else setError(supportErrorText(failure)); }
  }
  async function loadMore() {
    try { const data = await supportRequest(`/api/admin/support/conversations?offset=${rooms.length}`); setRooms(old => [...old, ...data.conversations.filter(room => !old.some(existing => existing.id === room.id))]); setMore(data.hasMore); }
    catch (failure) { if ([401,403].includes(failure.status)) expired(); else setError(supportErrorText(failure)); }
  }
  return <div className="support-admin-grid">
    <aside className="support-conversations" aria-label="상담방 목록"><h2>상담 목록</h2>
      {!rooms.length && <p className="support-empty">아직 접수된 상담이 없어요.</p>}
      {rooms.map(room => <button type="button" className={room.id === selectedId ? 'selected' : ''} key={room.id} onClick={() => setSelectedId(room.id)} aria-pressed={room.id === selectedId}>
        <strong>방문자 {room.id.slice(0,8)}</strong><span>{room.status === 'OPEN' ? '상담 중' : '종료'} {room.unreadCount > 0 && <b aria-label={`읽지 않은 메시지 ${room.unreadCount}개`}>{room.unreadCount}</b>}</span>
        <p>{room.lastMessage || '새 상담이 시작되었어요.'}</p><time>{new Date(room.lastMessageAt || room.createdAt).toLocaleString('ko-KR')}</time>
      </button>)}{more && <button type="button" onClick={loadMore}>이전 상담 더 보기</button>}
    </aside>
    <section className="support-admin-chat" aria-label="관리자 상담 대화"><header><h2>{selectedId ? `방문자 ${selectedId.slice(0,8)}` : '상담을 선택해 주세요'}</h2><span role="status">{connectionLabels[chat.status]}</span>
      {chat.conversation?.status === 'OPEN' && <button type="button" className="button button-outline" onClick={close}>상담 종료</button>}</header>
      {(error || chat.error) && <p className="support-error" role="alert">{error || chat.error}<button type="button" onClick={chat.restore}>대화 다시 확인</button></p>}
      {chat.conversation && <><SupportMessageList messages={chat.messages} pending={chat.pending} admin onRetry={message => chat.send(message.text, message)} />
        {chat.conversation.status === 'CLOSED' && <p className="support-closed">종료된 상담입니다.</p>}
        <SupportMessageInput disabled={chat.status !== 'connected' || chat.conversation.status === 'CLOSED'} onSend={chat.send} /></>}
    </section>
  </div>;
}
export default function AdminSupportPage() {
  const auth = useAuth(), enabled = useSupportEnabled();
  return <div className="container page-space support-admin"><span className="eyebrow">SUPPORT DESK</span><h1>고객센터 관리</h1>
    {auth.phase !== 'ready' ? <p role="status">관리자 권한 확인 중</p> : auth.user?.role !== 'ADMIN'
      ? <p>관리자 로그인이 필요합니다. <Link to="/login" state={{ from: '/admin/support' }}>로그인하기</Link></p>
      : !enabled ? <p>고객센터가 아직 활성화되지 않았습니다.</p> : <AdminConsole />}
  </div>;
}
