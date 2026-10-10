import minecraftData from 'minecraft-data';
import nbt from 'prismarine-nbt';

export const version = '26.2';
export const protocolVersion = 776;
const data = minecraftData(version);
if (data?.version.minecraftVersion !== version || data.version.version !== protocolVersion) {
  throw new Error('Native 26.2 protocol data missing; run npm run protocol:prepare then npm ci --ignore-scripts');
}

export function plainText(value) {
  const text = value?.type ? nbt.simplify(value) : value;
  if (typeof text === 'string') return text.replace(/\u00a7[0-9a-fk-or]/gi, '');
  if (Array.isArray(text)) return text.map(plainText).join('');
  return text && typeof text === 'object'
    ? plainText(text.text ?? text.translate ?? '') + plainText(text.extra ?? []) : '';
}

export function itemName(item) {
  return plainText(item?.components?.find(c => c.type === 'custom_name' || c.type === 'item_name')?.data);
}

export function cancelledMenuClick(window, slot) {
  if (!Number.isInteger(slot) || slot < 0 || slot >= 54) throw new Error('Invalid menu slot');
  // Menu clicks are cancelled server-side: no predicted slot changes, empty cursor.
  return { windowId: window.id, stateId: window.stateId, slot, mouseButton: 0,
    mode: 0, changedSlots: [], cursorItem: undefined };
}

export function inventoryClick(window, slot, button = 0, mode = 0) {
  if (!Number.isInteger(slot) || slot < 0 || slot >= window.items.length) throw new Error('Invalid inventory slot');
  if (![0, 1, 2, 6].includes(mode) || !Number.isInteger(button) || button < 0 || button > (mode === 2 ? 8 : 1)) {
    throw new Error('Invalid inventory click');
  }
  // No client-side inventory predictor: native server performs the click then resyncs its
  // authoritative state. Tests assert that state through the independent owner-thread probe.
  return { windowId: window.id, stateId: window.stateId, slot, mouseButton: button, mode,
    changedSlots: [], cursorItem: undefined };
}
