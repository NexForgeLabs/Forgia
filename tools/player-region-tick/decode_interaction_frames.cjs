'use strict'
// Independent decoding of preserved uncompressed protocol frames, not runner summaries.
const fs = require('node:fs')
const readline = require('node:readline')
const mc = require('minecraft-protocol')
const incoming = mc.createDeserializer({ state: 'play', version: '1.21.1', noErrorLogging: true })
const outgoing = mc.createDeserializer({ state: 'play', version: '1.21.1', isServer: true, noErrorLogging: true })
async function main () {
  for await (const line of readline.createInterface({ input: fs.createReadStream(process.argv[2]) })) {
    const row = JSON.parse(line)
    if (row.data.state !== 'play' || !['frame', 'frame_send'].includes(row.direction)) continue
    const buffer = Buffer.from(row.data.base64, 'base64')
    const wanted = row.direction === 'frame_send' ? [0x16] : [0x1a, 0x5d, 0x15, 0x13]
    if (!wanted.includes(buffer[0])) continue
    const packet = (row.direction === 'frame_send' ? outgoing : incoming).parsePacketBuffer(buffer)
    if (packet.metadata.size !== buffer.length) throw new Error('partial relevant frame: ' + row.seq)
    console.log(JSON.stringify({ seq: row.seq, nano: row.nano, bot: row.bot, direction: row.direction,
      name: packet.data.name, data: packet.data.params }, (key, value) => typeof value === 'bigint' ? value.toString() : value))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
