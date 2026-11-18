'use strict'
// Decode the original saved artifact, without starting a server or mutating its data.
const fs = require('node:fs')
const nbt = require('prismarine-nbt')
async function main () {
  const { parsed } = await nbt.parse(fs.readFileSync(process.argv[2]))
  console.log(JSON.stringify(nbt.simplify(parsed), (key, value) => typeof value === 'bigint' ? value.toString() : value))
}
main().catch(error => { console.error(error); process.exitCode = 1 })
