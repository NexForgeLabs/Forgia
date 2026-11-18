'use strict'
// P5 real TCP clients, retaining only explicitly selected raw protocol frames; original parser/game actions unchanged. stdin/stdout is only the test driver's control plane; game actions use packets.
const mc = require('minecraft-protocol')
const fs = require('node:fs')
const readline = require('node:readline')
const crypto = require('node:crypto')
const clients = new Map()
const gameplayCapture = true
let loadTimer = null
let loadRound = 0
const protocol = require('minecraft-data')('1.21.1').protocol
const retainedPackets = new Set(['ping_request', 'ping_response', 'position', 'position_look', 'teleport_confirm', 'keep_alive', 'use_entity', 'update_health', 'damage_event', 'held_item_slot', 'window_items', 'set_slot', 'custom_payload', 'kick_disconnect', 'disconnect', 'chat_message', 'system_chat', 'player_chat', 'respawn', 'client_command'])
if (gameplayCapture) for (const name of ['use_item', 'block_dig', 'block_place', 'block_change', 'multi_block_change', 'acknowledge_player_digging', 'window_click', 'close_window', 'steer_vehicle', 'vehicle_move', 'map', 'open_window', 'attach_entity', 'set_passengers', 'experience', 'collect', 'entity_destroy', 'spawn_entity']) retainedPackets.add(name)
function retainFrame (buffer, state, side) {
  const types = protocol[state]?.[side]?.types
  if (!types) return false
  const mappings = types.packet[1][0].type[1].mappings
  let id = 0; let shift = 0
  for (const byte of buffer) { id |= (byte & 0x7f) << shift; if (!(byte & 0x80)) break; shift += 7; if (shift > 28) throw new Error('invalid packet id') }
  return retainedPackets.has(Object.entries(mappings).find(([code]) => Number(code) === id)?.[1])
}
const log = fs.createWriteStream(process.argv[2], { flags: 'wx' })
let sequence = 0
function record (bot, direction, name, data) {
  log.write(JSON.stringify({ seq: ++sequence, nano: process.hrtime.bigint().toString(), bot, direction, name, data },
    (key, value) => typeof value === 'bigint' ? value.toString() : value) + '\n')
}
function send (bot, name, data) {
  if (name === 'ping_request' && !Array.isArray(data.id)) data = { ...data, id: BigInt(data.id) }
  bot.client.write(name, data)
}
function waitFor (condition, timeout = 20000) {
  return new Promise((resolve, reject) => {
    const end = Date.now() + timeout
    const timer = setInterval(() => {
      if (condition()) { clearInterval(timer); resolve() } else if (Date.now() >= end) {
        clearInterval(timer); reject(new Error('protocol condition timeout'))
      }
    }, 20)
  })
}
async function execute (request) {
  const { op, name } = request
  if (op === 'connect') {
    if (clients.has(name)) throw new Error('duplicate client')
    const bot = { name, client: mc.createClient({ host: '127.0.0.1', port: request.port, username: name, auth: 'offline', version: '1.21.1', disableChatSigning: true }), position: null, health: null, ended: false }
    clients.set(name, bot)
    const originalWrite = bot.client.write.bind(bot.client)
    bot.client.write = (packet, data) => {
      record(name, 'send', packet, data)
      return originalWrite(packet, data)
    }
    const capturedSerializers = new WeakSet()
    function captureFrames () {
      const serializer = bot.client.serializer
      if (serializer && !capturedSerializers.has(serializer)) {
        capturedSerializers.add(serializer)
        serializer.on('data', buffer => { if (!retainFrame(buffer, bot.client.state, 'toServer')) return; record(name, 'frame_send', 'serialized', {
          state: bot.client.state, bytes: buffer.length, sha256: crypto.createHash('sha256').update(buffer).digest('hex'), base64: buffer.toString('base64')
        }) })
      }
      const parser = bot.client.deserializer
      const originalTransform = parser._transform
      parser._transform = function (buffer, encoding, callback) {
        let id = 0; let shift = 0
        for (const byte of buffer) {
          id |= (byte & 0x7f) << shift
          if (!(byte & 0x80)) break
          shift += 7
          if (shift > 28) throw new Error('invalid raw packet id')
        }
        if (retainFrame(buffer, bot.client.state, 'toClient')) record(name, 'frame', String(id), { state: bot.client.state, bytes: buffer.length, sha256: crypto.createHash('sha256').update(buffer).digest('hex'), base64: buffer.toString('base64') })
        return originalTransform.call(this, buffer, encoding, callback)
      }
    }
    captureFrames()
    bot.client.on('state', captureFrames)
    bot.client.on('error', error => { record(name, 'error', 'error', String(error)); bot.error = String(error) })
    bot.client.on('end', reason => { bot.ended = true; record(name, 'end', 'end', String(reason)) })
    bot.client.on('packet', (data, meta) => {
      if (meta.state !== 'play') record(name, 'receive', meta.name, ['custom_payload', 'ping', 'success', 'finish_configuration'].includes(meta.name) ? data : { state: meta.state })
      else if (retainedPackets.has(meta.name)) record(name, 'decoded', meta.name, {})
      if (['login', 'position', 'update_health', 'hurt_animation', 'damage_event', 'entity_status', 'respawn', 'kick_disconnect', 'disconnect', 'block_change', 'multi_block_change', 'acknowledge_player_digging', 'open_window', 'window_items', 'set_slot', 'experience', 'collect', 'entity_destroy', 'entity_metadata', 'spawn_entity', 'ping_response', 'keep_alive', 'custom_payload', 'system_chat', 'player_chat', 'profileless_chat', 'game_state_change', 'start_configuration'].includes(meta.name)) record(name, 'receive', meta.name, data)
    })
    bot.client.on('ping', packet => send(bot, 'pong', { id: packet.id }))
    bot.client.on('login', packet => { bot.id = packet.entityId })
    if (gameplayCapture) {
      bot.entities = new Map()
      bot.client.on('spawn_entity', packet => bot.entities.set(packet.entityId, packet))
      bot.client.on('entity_destroy', packet => { for (const id of packet.entityIds) bot.entities.delete(id) })
    }
    bot.client.on('open_window', packet => { bot.windowId = packet.windowId })
    bot.client.on('window_items', packet => { bot.window = packet })
    bot.client.on('set_slot', packet => {
      if (bot.window && packet.windowId === bot.window.windowId) { bot.window.stateId = packet.stateId; bot.window.items[packet.slot] = packet.item }
    })
    bot.client.on('update_health', packet => { bot.health = packet.health })
    bot.client.on('game_state_change', packet => { if (packet.reason === 'win_game') bot.credits = true })
    bot.client.on('position', packet => {
      const previous = bot.position || { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 }
      const flags = typeof packet.flags === 'object' ? packet.flags._value : (packet.flags || 0)
      bot.position = {}
      for (const [index, field] of ['x', 'y', 'z', 'yaw', 'pitch'].entries()) bot.position[field] = packet[field] + ((flags & (1 << index)) ? previous[field] : 0)
      bot.lastTeleport = packet.teleportId
      send(bot, 'teleport_confirm', { teleportId: packet.teleportId })
      // Match the client's next simulation step instead of racing a command teleport's original
      // authoritative reset in the same server tick. A newer teleport invalidates this old reply.
      setTimeout(() => {
        if (!bot.ended && bot.lastTeleport === packet.teleportId) send(bot, 'position_look', { ...bot.position, onGround: true })
      }, 50)
    })
    bot.client.on('chunk_batch_finished', () => send(bot, 'chunk_batch_received', { chunksPerTick: 8 }))
    await waitFor(() => bot.position !== null || bot.ended)
    if (bot.ended || bot.error) throw new Error(bot.error || 'connection ended before spawn')
    return { id: bot.id, position: bot.position, health: bot.health }
  }
  if (op === 'load_start') {
    if (loadTimer) throw new Error('load already active')
    loadRound = 0
    const actors = request.actors
    loadTimer = setInterval(() => {
      loadRound++
      for (const [index, actor] of actors.entries()) {
        const target = clients.get(actor.name)
        if (!target || target.ended || target.client.state !== 'play') { record(actor.name, 'load_error', 'disconnected', {}); continue }
        const position = { x: actor.x + (loadRound % 2 ? 0.05 : 0), y: actor.y, z: actor.z, yaw: 0, pitch: 0, onGround: true }
        send(target, 'position_look', position)
        send(target, 'held_item_slot', { slotId: loadRound % 9 })
        send(target, 'ping_request', { id: BigInt((request.baseId || 1000000) + loadRound * 64 + index) })
      }
    }, request.intervalMillis || 500)
    return { actors: actors.length }
  }
  if (op === 'load_stop') { if (loadTimer) clearInterval(loadTimer); loadTimer = null; return { rounds: loadRound } }
  if (op === 'mark') { record(null, 'control', request.label, {}); return { marked: request.label } }
  const bot = clients.get(name)
  if (op === 'batch') {
    for (const action of request.actions) send(clients.get(action.name), action.packet, action.data)
    return { sent: request.actions.length }
  }
  if (op === 'quick_move') {
    for (const key of request.names) {
      const target = clients.get(key)
      const window = target.window
      if (!window || window.windowId !== target.windowId) throw new Error('no actual open container')
      const item = window.items[0]
      send(target, 'window_click', { windowId: window.windowId, stateId: window.stateId, slot: 0, mouseButton: 0, mode: 1,
        changedSlots: [{ location: 0, item: { itemCount: 0 } }, { location: window.items.length - 1, item }], cursorItem: { itemCount: 0 } })
    }
    return { sent: request.names.length }
  }
  if (op === 'entities') return [...bot.entities.values()]
  if (op === 'snapshot') return Object.fromEntries([...clients].map(([key, value]) => [key, { id: value.id, position: value.position, health: value.health, state: value.client.state, ended: value.ended, windowId: value.windowId, window: value.window }]))
  if (op === 'network_sequence') {
    const channel = Buffer.from('forgia:lifecycle_probe', 'utf8')
    send(bot, 'custom_payload', { channel: 'minecraft:register', data: channel })
    bot.client.chat(request.text)
    send(bot, 'ping_request', { id: BigInt(request.id) })
    send(bot, 'custom_payload', { channel: 'minecraft:unregister', data: channel })
    return { sent: true }
  }
  if (op === 'wait_credits') { await waitFor(() => bot.credits || bot.ended); if (!bot.credits) throw new Error('ended without credits'); return { credits: true } }
  if (op === 'wait_play') { await waitFor(() => bot.client.state === 'play' || bot.ended); if (bot.ended) throw new Error('ended before returning to play'); return { state: bot.client.state } }
  if (op === 'chat') { if (bot.client.state !== 'play') throw new Error('chat outside play'); bot.client.chat(request.text); return { sent: true } }
  if (op === 'payload') {
    if (bot.client.state !== 'play') throw new Error('payload outside play')
    const data = Buffer.from(request.text, 'utf8')
    if (data.length > 127) throw new Error('probe text too long')
    send(bot, 'custom_payload', { channel: request.channel || 'forgia_p2:probe', data: Buffer.concat([Buffer.from([data.length]), data]) })
    return { sent: true }
  }
  if (op === 'register_channels') {
    send(bot, 'custom_payload', { channel: 'minecraft:register', data: Buffer.from(request.channels.join('\0'), 'utf8') })
    return { sent: true }
  }
  if (op === 'packet') { send(bot, request.packet, request.data); return { sent: true, nano: process.hrtime.bigint().toString() } }
  if (op === 'disconnect') { bot.client.end('test disconnect'); await waitFor(() => bot.ended); clients.delete(name); return { ended: true } }
  if (op === 'close') {
    if (loadTimer) clearInterval(loadTimer)
    loadTimer = null
    for (const value of clients.values()) value.client.end('test complete')
    await waitFor(() => [...clients.values()].every(value => value.ended))
    await new Promise(resolve => log.end(resolve))
    return { closed: true }
  }
  throw new Error('unknown operation: ' + op)
}
const input = readline.createInterface({ input: process.stdin })
let chain = Promise.resolve()
input.on('line', line => {
  chain = chain.then(async () => {
    let request
    try {
      request = JSON.parse(line)
      const result = await execute(request)
      process.stdout.write(JSON.stringify({ requestId: request.requestId, result }) + '\n')
      if (request.op === 'close') process.exit(0)
    } catch (error) {
      process.stdout.write(JSON.stringify({ requestId: request?.requestId, error: String(error) }) + '\n')
    }
  })
})
