'use strict'
// Decode only the relevant preserved 1.21.1 play frames; retain original recipe diagnostics elsewhere.
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
    const wanted = row.direction === 'frame_send' ? [0x09, 0x0e, 0x0f, 0x16, 0x1b, 0x24, 0x2f, 0x38] : [0x01, 0x02, 0x04, 0x05, 0x09, 0x12, 0x13, 0x15, 0x33, 0x42, 0x47, 0x49, 0x5c, 0x5d, 0x6f]
    if (!wanted.includes(buffer[0])) continue
    const packet = (row.direction === 'frame_send' ? outgoing : incoming).parsePacketBuffer(buffer)
    if (packet.metadata.size !== buffer.length) throw new Error('partial relevant frame ' + row.seq)
    console.log(JSON.stringify({ seq: row.seq, bot: row.bot, nano: row.nano, direction: row.direction,
      name: packet.data.name, data: packet.data.params }, (key, value) => typeof value === 'bigint' ? value.toString() : value))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
