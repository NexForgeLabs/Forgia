'use strict'
// Independent wire decode for the bounded P2 protocol evidence, including configuration transitions.
const fs = require('node:fs')
const readline = require('node:readline')
const mc = require('minecraft-protocol')
const protocol = require('minecraft-data')('1.21.1').protocol
const decoders = new Map()
const wanted = new Set(['chat_message', 'chat_command', 'chat_command_signed', 'custom_payload', 'ping_request', 'ping_response', 'keep_alive', 'position', 'position_look', 'teleport_confirm', 'respawn', 'game_state_change', 'client_command', 'start_configuration', 'configuration_acknowledged', 'finish_configuration', 'system_chat', 'player_chat', 'profileless_chat', 'kick_disconnect', 'disconnect', 'steer_vehicle', 'vehicle_move'])
async function main () {
  for await (const line of readline.createInterface({ input: fs.createReadStream(process.argv[2]) })) {
    const row = JSON.parse(line)
    if (!['frame', 'frame_send'].includes(row.direction)) continue
    const state = row.data.state
    if (!['play', 'configuration', 'login'].includes(state)) continue
    const key = state + ':' + row.direction
    if (!decoders.has(key)) decoders.set(key, mc.createDeserializer({ state, version: '1.21.1', isServer: row.direction === 'frame_send', noErrorLogging: true }))
    const buffer = Buffer.from(row.data.base64, 'base64')
    let id = 0; let shift = 0
    for (const byte of buffer) { id |= (byte & 0x7f) << shift; if (!(byte & 0x80)) break; shift += 7; if (shift > 28) throw new Error('invalid original packet id') }
    const mapping = protocol[state][row.direction === 'frame_send' ? 'toServer' : 'toClient'].types.packet[1][0].type[1].mappings
    const expected = Object.entries(mapping).find(([code]) => Number(code) === id)?.[1]
    if (!wanted.has(expected)) continue
    const packet = decoders.get(key).parsePacketBuffer(buffer)
    if (packet.data.name !== expected) throw new Error('original packet ID/name mismatch')
    if (packet.metadata.size !== buffer.length) throw new Error('partial original protocol frame ' + row.seq)
    if (!wanted.has(packet.data.name)) continue
    console.log(JSON.stringify({ seq: row.seq, bot: row.bot, nano: row.nano, direction: row.direction, state, name: packet.data.name, data: packet.data.params }, (key, value) => typeof value === 'bigint' ? value.toString() : value))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
