import { useCallback, useEffect, useRef, useState } from 'react';
import { mergeMessages, recoverMessages, supportRequest, supportErrorText, newClientMessageId } from './api.js';
import { useSupportWebSocket } from './useSupportWebSocket.js';

export function useSupportConversation({ enabled, admin = false, selectedId = null, onRoomsChanged, onExpired }) {
  const [conversation, setConversation] = useState(null), [messages, setMessages] = useState([]), [pending, setPending] = useState([]), [error, setError] = useState('');
  const [starting, setStarting] = useState(false);
  const room = useRef(null), version = useRef(0), messagesRef = useRef([]), loading = useRef(null);
  const expire = useRef(onExpired); expire.current = onExpired;
  messagesRef.current = messages;
  function received(incoming) {
    setMessages(old => mergeMessages(old, incoming));
    const ids = new Set(incoming.filter(m => m.senderType === (admin ? 'ADMIN' : 'VISITOR')).map(m => m.clientMessageId));
    setPending(old => old.filter(m => !ids.has(m.clientMessageId)));
  }
  const restore = useCallback(async () => {
    const stamp = version.current;
    if (!enabled || loading.current === stamp) return;
    loading.current = stamp;
    try {
      let currentId = admin ? selectedId : (await supportRequest('/api/support/conversations/current')).conversation?.id;
      if (stamp !== version.current) return;
      if (!currentId) { room.current = null; setConversation(null); setMessages([]); setPending([]); return; }
      const previousId = room.current;
      let after = 0;
      if (previousId === currentId) for (const message of messagesRef.current) {
        if (message.sequence === after + 1) after++;
        else if (message.sequence > after + 1) break;
      }
      const recovered = await recoverMessages(currentId, admin, after);
      if (stamp !== version.current) return;
      room.current = currentId;
      if (previousId !== currentId) { setMessages([]); setPending([]); }
      setConversation(recovered.conversation); received(recovered.messages); setError('');
      if (admin) await supportRequest(`/api/admin/support/conversations/${currentId}/read`, { method: 'POST', body: { sequence: Math.max(after, recovered.messages.at(-1)?.sequence || 0) } });
    } catch (failure) {
      if (stamp !== version.current) return;
      if (admin && [401,403].includes(failure.status)) expire.current?.();
      setError(supportErrorText(failure));
    } finally { if (loading.current === stamp) loading.current = null; }
  }, [enabled, admin, selectedId]);
  const socket = useSupportWebSocket({ enabled, admin,
    onReady: () => { restore(); onRoomsChanged?.(); },
    onEvent: event => {
      onRoomsChanged?.(event);
      if (event.conversation?.id !== room.current) return;
      setConversation(event.conversation);
      if (event.message) {
        received([event.message]);
        // Recover gaps even when events arrive out of order; REST is authoritative.
        if (admin) restore();
      }
    },
    onError: failure => { setError(supportErrorText(failure)); setPending(old => old.map(m => !failure.clientMessageId || m.clientMessageId === failure.clientMessageId ? { ...m, failed: true } : m)); },
  });
  useEffect(() => {
    version.current++; room.current = null; messagesRef.current = []; setConversation(null); setMessages([]); setPending([]); setError('');
    restore();
    return () => { version.current++; };
  }, [restore]);
  useEffect(() => {
    if (socket.status === 'expired') expire.current?.();
    if (socket.status !== 'connected') setPending(old => old.map(m => ({ ...m, failed: true })));
  }, [socket.status]);
  useEffect(() => {
    const timer = setInterval(() => setPending(old => old.map(m => !m.failed && Date.now() - m.sentAt > 12000 ? { ...m, failed: true } : m)), 2000);
    return () => clearInterval(timer);
  }, []);
  async function start() {
    if (starting || room.current) return;
    const stamp = ++version.current;
    setStarting(true);
    try {
      const created = await supportRequest('/api/support/conversations', { method: 'POST' });
      if (stamp !== version.current) return;
      room.current = created.id; messagesRef.current = []; setConversation(created); setMessages([]); setPending([]); setError(''); await restore();
    } catch (failure) { if (stamp === version.current) setError(supportErrorText(failure)); }
    finally { if (stamp === version.current) setStarting(false); }
  }
  function send(text, retry) {
    if (!conversation || conversation.status !== 'OPEN') return;
    const request = retry || { conversationId: conversation.id, clientMessageId: newClientMessageId(), text };
    const sent = socket.send({ conversationId: request.conversationId, clientMessageId: request.clientMessageId, text: request.text });
    setPending(old => [...old.filter(m => m.clientMessageId !== request.clientMessageId), { ...request, sentAt: Date.now(), failed: !sent }]);
  }
  return { conversation, messages, pending, error, starting, status: socket.status, start, send, restore };
}
