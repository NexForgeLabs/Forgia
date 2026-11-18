'use strict'
// Decode retained original frames without trusting the driver's parsed feedback.
const fs = require('node:fs')
const readline = require('node:readline')
const mc = require('minecraft-protocol')
const decoders = new Map()
async function main () {
  for await (const line of readline.createInterface({ input: fs.createReadStream(process.argv[2]) })) {
    const row = JSON.parse(line)
    if (!['frame', 'frame_send'].includes(row.direction) || row.data.state !== 'play') continue
    const key = row.direction
    if (!decoders.has(key)) decoders.set(key, mc.createDeserializer({ state: 'play', version: '1.21.1', isServer: key === 'frame_send', noErrorLogging: true }))
    const bytes = Buffer.from(row.data.base64, 'base64')
    const packet = decoders.get(key).parsePacketBuffer(bytes)
    if (packet.metadata.size !== bytes.length) throw new Error('Incomplete original operation frame ' + row.seq)
    console.log(JSON.stringify({ seq: row.seq, nano: row.nano, bot: row.bot, direction: row.direction, name: packet.data.name, data: packet.data.params },
      (key, value) => typeof value === 'bigint' ? value.toString() : value))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
