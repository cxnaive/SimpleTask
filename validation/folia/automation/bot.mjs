import mc from 'minecraft-protocol';
import nbt from 'prismarine-nbt';
import { until } from './harness.mjs';
import { plainText, version } from './protocol.mjs';

export function connectBot(username, port, record, checkServer) {
  const client = mc.createClient({ host: '127.0.0.1', port, username, version,
    auth: 'offline', disableChatSigning: true, hideErrors: true,
    clientSettings: { locale: 'zh_cn', viewDistance: 2 } });
  const state = { messages: [], sounds: [], titles: [], actionBars: [], bosses: [],
    windows: [], current: null, position: null, ended: false, closing: false, error: null };
  const emit = (event, value) => record({ bot: username, event, value });
  client.on('error', error => { state.error = error; emit('error', error.message); });
  client.on('end', reason => { state.ended = true; emit('end', reason); });
  client.on('kick_disconnect', packet => { state.error = new Error(`Kicked: ${plainText(packet.reason)}`); });
  client.on('position', packet => {
    const previous = state.position ?? { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 };
    state.position = Object.fromEntries(['x','y','z','yaw','pitch'].map(key =>
      [key, packet[key] + (packet.flags[key] ? previous[key] : 0)]));
    client.write('teleport_confirm', { teleportId: packet.teleportId });
    client.write('player_loaded', {});
    client.write('position_look', { ...state.position, flags: { onGround: true, hasHorizontalCollision: false } });
    emit('position', state.position);
  });
  client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 16 }));
  client.on('system_chat', packet => {
    const component = packet.content?.type ? nbt.simplify(packet.content) : packet.content;
    const message = { text: plainText(component), component };
    if (packet.isActionBar) state.actionBars.push(message); else state.messages.push(message);
    emit(packet.isActionBar ? 'actionbar' : 'chat', message);
  });
  client.on('sound_effect', packet => { state.sounds.push(packet); emit('sound', packet); });
  client.on('set_title_text', packet => { state.titles.push({ text: plainText(packet.text), packet }); emit('title', packet); });
  client.on('action_bar', packet => { state.actionBars.push({ text: plainText(packet.text) }); emit('actionbar', packet); });
  client.on('boss_bar', packet => { state.bosses.push(packet); emit('bossbar', packet); });
  client.on('open_window', packet => {
    const window = { id: packet.windowId, type: packet.inventoryType, title: plainText(packet.windowTitle),
      stateId: null, items: null };
    state.windows.push(window); state.current = window; emit('open_window', packet);
  });
  client.on('window_items', packet => {
    if (state.current?.id === packet.windowId) {
      state.current.stateId = packet.stateId; state.current.items = packet.items;
    }
    emit('window_items', packet);
  });
  client.on('set_slot', packet => {
    if (state.current?.id === packet.windowId) {
      state.current.stateId = packet.stateId; state.current.items[packet.slot] = packet.item;
    }
  });
  client.on('close_window', packet => { if (state.current?.id === packet.windowId) state.current = null; });
  const ticking = setInterval(() => {
    if (client.state === 'play' && !state.ended && !state.closing) client.write('tick_end', {});
  }, 50);
  const check = () => {
    checkServer();
    if (state.error) throw state.error;
    if (state.ended && !state.closing) throw new Error(`${username} disconnected unexpectedly`);
  };
  const wait = (label, condition, timeout = 15_000) => until(`${username}: ${label}`, () => {
    check(); return condition();
  }, { timeout });
  return {
    username, client, state, wait,
    ready: () => wait('login and position', () => state.position, 60_000),
    command(text) { check(); client.chat(text); emit('send', text); },
    click(slot, mouseButton = 0) {
      check();
      if (!state.current?.items) throw new Error('No menu received');
      client.write('window_click', { windowId: state.current.id, stateId: state.current.stateId,
        slot, mouseButton, mode: 0, changedSlots: [], cursorItem: undefined });
      emit('click', { slot, mouseButton });
    },
    closeWindow() {
      if (state.current) client.write('close_window', { windowId: state.current.id });
      state.current = null;
    },
    async close() {
      state.closing = true; clearInterval(ticking);
      if (!state.ended) client.end('validation complete');
      try { await until('client closed', () => state.ended, { timeout: 3000 }); }
      catch { client.socket?.destroy(); }
    }
  };
}
