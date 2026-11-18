'use strict'
// Independent protocol processes, at most eight real clients per process. All original child
// captures remain beside the globally time-ordered capture; no server/protocol timeout changes.
const fs = require('node:fs')
const path = require('node:path')
const readline = require('node:readline')
const { spawn } = require('node:child_process')
const output = process.argv[2]
if (fs.existsSync(output)) throw new Error('Preserve original grouped capture')
fs.writeFileSync(output, '', { flag: 'wx' })
const groups = []
const owners = new Map()
const controls = []
let requestId = 0
function createGroup () {
  const index = groups.length
  const capture = output + '.group-' + index + '.ndjson'
  const error = fs.openSync(output + '.group-' + index + '.stderr.log', 'wx')
  const child = spawn(process.execPath, [path.join(__dirname, 'scale_clients.cjs'), capture], { stdio: ['pipe', 'pipe', error] })
  fs.closeSync(error)
  const stdout = fs.createWriteStream(output + '.group-' + index + '.stdout.log', { flags: 'wx' })
  const group = { index, child, capture, names: new Set(), pending: new Map(), closed: false, failure: null, diagnosticLines: 0, stdout }
  readline.createInterface({ input: child.stdout }).on('line', line => {
    stdout.write(line + '\n')
    let value
    // minecraft-protocol can emit non-control recipe/registry diagnostics on stdout. The
    // original Session reader already skips these; retain them verbatim instead of treating
    // them as a control reply. Actual client error/end records remain strict verifier inputs.
    try { value = JSON.parse(line) } catch (error) { group.diagnosticLines++; return }
    if (!value || !Object.hasOwn(value, 'requestId')) { group.diagnosticLines++; return }
    const pending = group.pending.get(value.requestId)
    if (!pending) { group.failure = 'Unexpected child response ' + line; return }
    group.pending.delete(value.requestId)
    clearTimeout(pending.timer)
    if (value.error) pending.reject(new Error(value.error)); else pending.resolve(value.result)
  })
  child.on('error', error => { group.failure = String(error) })
  child.on('exit', (code, signal) => {
    group.exit = code; group.signal = signal; group.closed = true
    stdout.end()
    if (code !== 0) group.failure = 'Child exit ' + code + ' / ' + signal
    for (const pending of group.pending.values()) { clearTimeout(pending.timer); pending.reject(new Error('Child exited before reply')) }
    group.pending.clear()
  })
  groups.push(group)
  return group
}
function call (group, request) {
  return new Promise((resolve, reject) => {
    if (group.closed || group.failure) return reject(new Error(group.failure || 'Closed client process'))
    const id = ++requestId
    const timer = setTimeout(() => { group.pending.delete(id); reject(new Error('Child request timeout ' + request.op)) }, 25000)
    group.pending.set(id, { resolve, reject, timer })
    group.child.stdin.write(JSON.stringify({ ...request, requestId: id }) + '\n')
  })
}
async function merge () {
  const events = [...controls]
  for (const group of groups) {
    let sequence = 0
    for await (const line of readline.createInterface({ input: fs.createReadStream(group.capture) })) {
      const row = JSON.parse(line)
      if (row.seq !== ++sequence) throw new Error('Original child sequence missing')
      events.push({ ...row, childGroup: group.index, childSeq: row.seq })
    }
  }
  events.sort((a, b) => BigInt(a.nano) < BigInt(b.nano) ? -1 : BigInt(a.nano) > BigInt(b.nano) ? 1 : (a.childGroup ?? -1) - (b.childGroup ?? -1))
  const stream = fs.createWriteStream(output, { flags: 'a' })
  for (const [index, row] of events.entries()) {
    if (!stream.write(JSON.stringify({ ...row, seq: index + 1 }) + '\n')) await new Promise(resolve => stream.once('drain', resolve))
  }
  await new Promise(resolve => stream.end(resolve))
  fs.writeFileSync(output + '.groups.json', JSON.stringify({ schema: 1, maxClientsPerProcess: 8, groups: groups.map(group => ({
    index: group.index, capture: group.capture, names: [...group.names], exit: group.exit, signal: group.signal, failure: group.failure,
    nonControlDiagnosticLines: group.diagnosticLines
  })) }, null, 2) + '\n', { flag: 'wx' })
}
async function execute (request) {
  if (request.op === 'connect') {
    if (owners.has(request.name)) throw new Error('Duplicate actor')
    const group = groups.find(group => group.names.size < 8) || createGroup()
    const result = await call(group, request)
    group.names.add(request.name); owners.set(request.name, group)
    return result
  }
  if (request.op === 'mark') {
    controls.push({ nano: process.hrtime.bigint().toString(), bot: null, direction: 'control', name: request.label, data: {} })
    return { marked: request.label }
  }
  if (request.op === 'snapshot') return Object.assign({}, ...await Promise.all(groups.map(group => call(group, request))))
  if (request.op === 'load_start') {
    await Promise.all(groups.map(group => call(group, { ...request, actors: request.actors.filter(actor => group.names.has(actor.name)) })))
    return { actors: request.actors.length }
  }
  if (request.op === 'load_stop') {
    const rows = await Promise.all(groups.map(group => call(group, request)))
    return { rounds: Math.min(...rows.map(row => row.rounds)), groups: rows }
  }
  if (request.op === 'close') {
    let failure
    const replies = await Promise.allSettled(groups.map(group => call(group, request)))
    for (const reply of replies) if (reply.status === 'rejected') failure = String(reply.reason)
    await Promise.all(groups.map(group => group.closed ? Promise.resolve() : new Promise(resolve => group.child.once('exit', resolve))))
    await merge()
    if (failure || groups.some(group => group.failure || group.exit !== 0)) throw new Error(failure || 'Grouped client failure')
    return { closed: true, groups: groups.length }
  }
  const group = owners.get(request.name)
  if (!group) throw new Error('No owning client process for ' + request.name)
  return call(group, request)
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
    } catch (error) { process.stdout.write(JSON.stringify({ requestId: request?.requestId, error: String(error) }) + '\n') }
  })
})
