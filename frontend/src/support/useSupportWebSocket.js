import { useEffect, useRef, useState } from 'react';
import { SupportChannel } from './channel.js';

export function useSupportWebSocket({ enabled, admin = false, onEvent, onError, onReady }) {
  const handlers = useRef({ onEvent, onError, onReady }); handlers.current = { onEvent, onError, onReady };
  const active = useRef(null);
  const [status, setStatus] = useState('disconnected');
  useEffect(() => {
    if (!enabled) { setStatus('disconnected'); return; }
    const connection = new SupportChannel({ admin, onEvent: event => handlers.current.onEvent?.(event),
      onError: error => handlers.current.onError?.(error), onReady: () => handlers.current.onReady?.(), onState: setStatus });
    active.current = connection; connection.start();
    // A periodic read also covers the small SUBSCRIBE/REST snapshot race and missed notifications.
    const recovery = setInterval(() => handlers.current.onReady?.(), 15000);
    return () => { clearInterval(recovery); active.current = null; connection.stop(); };
  }, [enabled, admin]);
  return { status, send: body => active.current?.send(body) || false };
}
