import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { SupportChannel } from '../src/support/channel.js';
import { mergeMessages, newClientMessageId, supportErrorText, supportRequest, SupportError } from '../src/support/api.js';

function harness(admin = false, extra = {}) {
  let options; const received = [], failures = [], states = [], subscriptions = [], sent = [];
  const fake = { connected: false, activate() {}, deactivate() { this.connected = false; }, subscribe(path, receive) { subscriptions.push({ path, receive }); }, publish(message) { sent.push(message); } };
  const channel = new SupportChannel({ admin, origin: 'http://192.168.0.19:8088', onEvent: event => received.push(event), onError: error => failures.push(error), onState: value => states.push(value), onReady: () => states.push('ready'),
    clientFactory: config => { options = config; return fake; }, csrf: async () => {}, headers: () => ({ 'X-CSRF-TOKEN': 'masked-test-token' }), session: async () => ({ user: { role: 'ADMIN' } }), ...extra });
  return { channel, fake, options, received, failures, states, subscriptions, sent };
}
test('LAN HTTP connects with ws and a CSRF CONNECT header; only personal subscriptions are made', async () => {
  const h = harness(); h.channel.start(); await h.options.beforeConnect(); h.fake.connected = true; h.options.onConnect();
  assert.equal(h.options.brokerURL, 'ws://192.168.0.19:8088/ws/support');
  assert.equal(h.fake.connectHeaders['X-CSRF-TOKEN'], 'masked-test-token');
  assert.deepEqual(h.subscriptions.map(s => s.path), ['/user/queue/support.events','/user/queue/support.errors']);
  h.subscriptions[0].receive({ body: '{"type":"MESSAGE"}' }); assert.equal(h.received[0].type,'MESSAGE');
  h.channel.send({ conversationId: 'room', text: '<script>alert(1)</script>' });
  assert.equal(h.sent[0].destination, '/app/support/visitor.send');
  assert.deepEqual(h.states, ['connecting','connected','ready']);
});
test('offline sends fail and stop suppresses stale events, reconnects and publishes', async () => {
  const h = harness(); assert.equal(h.channel.send({text:'offline'}),false);
  h.fake.connected = true; h.options.onConnect(); await h.channel.stop();
  h.subscriptions[0].receive({body:'{"type":"MESSAGE"}'}); h.options.onWebSocketClose(); h.options.onConnect();
  assert.equal(h.received.length,0); assert.equal(h.channel.send({text:'after stop'}),false); assert.equal(h.subscriptions.length,2);
});
test('each reconnect refreshes CSRF and recovers through REST; administrator channels require current authentication', async () => {
  let refreshes = 0;
  const h = harness(true,{ csrf: async () => { refreshes++; } });
  await h.options.beforeConnect(); h.fake.connected=true; h.options.onConnect();
  h.options.onWebSocketClose(); await h.options.beforeConnect(); h.options.onConnect();
  assert.equal(refreshes,2); assert.equal(h.subscriptions[0].path,'/user/queue/support.admin');
  assert.equal(h.states.filter(x=>x==='ready').length,2);
  const expired = harness(true,{ session: async () => ({user:null}) });
  await expired.options.beforeConnect(); assert.ok(expired.states.includes('expired')); assert.equal(expired.channel.stopped,true);
});
test('StrictMode stop while asynchronous CSRF resolves cannot reopen an abandoned connection', async () => {
  let release; const h = harness(false,{csrf:()=>new Promise(resolve=>{release=resolve;})});
  const preparation = h.options.beforeConnect(); h.channel.stop(); release(); await preparation;
  assert.equal(h.fake.connectHeaders,undefined); h.options.onConnect(); assert.equal(h.subscriptions.length,0);
});
test('server IDs remove duplicated realtime/history events and sequence orders a conversation', () => {
  const previous = [{id:'b',sequence:2,text:'B'}];
  assert.deepEqual(mergeMessages(previous,[{id:'a',sequence:1,text:'A'},{id:'b',sequence:2,text:'B'}]).map(x=>x.id),['a','b']);
  assert.equal(previous.length,1);
});
test('client IDs use HTTP-compatible cryptographic randomness and safe errors never expose server text', () => {
  const ids = new Set(Array.from({length:100},newClientMessageId)); assert.equal(ids.size,100);
  for (const id of ids) assert.match(id,/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  assert.doesNotMatch(supportErrorText({code:'private database password',message:'private'}),/private|password/);
});
test('REST failures are typed and arbitrary upstream errors never reach the UI', async () => {
  const old = globalThis.fetch;
  try {
    globalThis.fetch = async () => ({ ok:false,status:503,json:async()=>({code:'password-from-upstream'}) });
    await assert.rejects(supportRequest('/api/support/config'),error=>error instanceof SupportError && error.code==='SUPPORT_UNAVAILABLE');
  } finally { globalThis.fetch=old; }
});

let vite, MessageList, Input;
before(async()=>{ vite=await createServer({server:{middlewareMode:true,hmr:false},appType:'custom'}); ({SupportMessageList:MessageList}=await vite.ssrLoadModule('/src/support/SupportMessageList.jsx')); ({SupportMessageInput:Input}=await vite.ssrLoadModule('/src/support/SupportMessageInput.jsx')); });
after(async()=>{await vite?.close();});
test('messages render as escaped text, timestamps and explicit unconfirmed pending states',()=>{
  const html=renderToStaticMarkup(createElement(MessageList,{messages:[{id:'a',senderType:'ADMIN',text:'<img src=x onerror="window.hacked=true">',createdAt:'2026-10-24T00:00:00Z'}],pending:[{clientMessageId:'b',text:'waiting',failed:true}],onRetry:()=>{}}));
  assert.match(html,/&lt;img/); assert.doesNotMatch(html,/<img/); assert.match(html,/전송 결과 확인 필요/); assert.match(html,/<time/);
  const input=renderToStaticMarkup(createElement(Input,{disabled:true,onSend:()=>{}})); assert.match(input,/maxLength="2000"/i); assert.match(input,/disabled/);
});
