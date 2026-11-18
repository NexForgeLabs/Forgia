'use strict'
const fs = require('node:fs')
const readline = require('node:readline')
const mc = require('minecraft-protocol')
const protocol = require('minecraft-data')('1.21.1').protocol.play
const wanted = new Set(['custom_payload', 'system_chat', 'window_items', 'set_slot', 'update_health', 'experience'])
const decoders = new Map()
async function main () {
  for await (const line of readline.createInterface({ input: fs.createReadStream(process.argv[2]) })) {
    const row = JSON.parse(line)
    if (!['frame', 'frame_send'].includes(row.direction) || row.data.state !== 'play') continue
    const buffer = Buffer.from(row.data.base64, 'base64')
    const side = row.direction === 'frame_send' ? 'toServer' : 'toClient'
    const mapping = protocol[side].types.packet[1][0].type[1].mappings
    let id = 0; let shift = 0
    for (const byte of buffer) { id |= (byte & 0x7f) << shift; if (!(byte & 0x80)) break; shift += 7; if (shift > 28) throw new Error('invalid packet id') }
    const expected = Object.entries(mapping).find(([code]) => Number(code) === id)?.[1]
    if (!wanted.has(expected)) continue
    if (!decoders.has(side)) decoders.set(side, mc.createDeserializer({ state: 'play', version: '1.21.1', isServer: side === 'toServer', noErrorLogging: true }))
    const packet = decoders.get(side).parsePacketBuffer(buffer)
    if (packet.data.name !== expected || packet.metadata.size !== buffer.length) throw new Error('packet name/length mismatch')
    const data = packet.data.params
    if (packet.data.name === 'custom_payload' && data.channel.startsWith('forgia_region:')) {
      let length = 0; let shift = 0; let count = 0
      for (const byte of data.data) { count++; length |= (byte & 0x7f) << shift; if (!(byte & 0x80)) break; shift += 7; if (shift > 28) throw new Error('invalid string length') }
      if (length !== data.data.length - count) throw new Error('payload string length mismatch')
      data.text = data.data.subarray(count).toString('utf8')
    }
    console.log(JSON.stringify({ seq: row.seq, bot: row.bot, direction: row.direction, name: packet.data.name, data }))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
