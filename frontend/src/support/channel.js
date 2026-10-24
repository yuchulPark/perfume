import { Client } from '@stomp/stompjs';
import { csrfHeaders, refreshCsrf, getSession } from '../api/auth.js';

/** No secrets in browser storage, URLs or debug logs. Subscriptions are recreated on every connect. */
export class SupportChannel {
  constructor({ admin = false, onEvent, onError, onState, onReady, clientFactory = options => new Client(options), origin = globalThis.location?.origin,
    csrf = refreshCsrf, headers = csrfHeaders, session = getSession }) {
    this.stopped = false;
    const state = value => { if (!this.stopped) onState(value); };
    this.client = clientFactory({
      brokerURL: `${origin.replace(/^http/, 'ws')}/ws/support`, reconnectDelay: 3000, connectionTimeout: 10000,
      heartbeatIncoming: 10000, heartbeatOutgoing: 10000, discardWebsocketOnCommFailure: true,
      debug: () => {},
      beforeConnect: async () => {
        if (this.stopped) return;
        state('connecting');
        try {
          if (admin) {
            const identity = await session();
            if (identity.user?.role !== 'ADMIN') { state('expired'); this.stop(); return; }
          }
          await csrf();
          if (!this.stopped) this.client.connectHeaders = headers();
        } catch { state('reconnecting'); }
      },
      onConnect: () => {
        if (this.stopped) return;
        const receive = frame => { if (this.stopped) return; try { onEvent(JSON.parse(frame.body)); } catch { /* Unusable event is recovered through REST. */ } };
        this.client.subscribe(admin ? '/user/queue/support.admin' : '/user/queue/support.events', receive);
        this.client.subscribe('/user/queue/support.errors', frame => {
          if (!this.stopped) { try { onError(JSON.parse(frame.body)); } catch { onError({ code: 'SUPPORT_UNAVAILABLE' }); } }
        });
        state('connected'); onReady();
      },
      onWebSocketClose: () => state('reconnecting'),
      onWebSocketError: () => state('reconnecting'),
      onStompError: () => state('reconnecting'),
    });
    this.admin = admin;
  }
  start() { this.client.activate(); }
  stop() { this.stopped = true; return this.client.deactivate({ force: true }); }
  send(body) {
    if (this.stopped || !this.client.connected) return false;
    try {
      this.client.publish({ destination: this.admin ? '/app/support/admin.send' : '/app/support/visitor.send', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
      return true;
    } catch { return false; }
  }
}
