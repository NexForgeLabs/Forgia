#!/usr/bin/env python3
"""Read-only observations of local test processes and their loopback TCP queues."""
import json
from pathlib import Path
import threading
import time


def text(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def process(pid):
    stat = text(f'/proc/{pid}/stat')
    if stat is None:
        return None
    fields = stat.rsplit(')', 1)[1].split()
    return dict(state=fields[0], userTicks=int(fields[11]), systemTicks=int(fields[12]),
                schedstat=text(f'/proc/{pid}/schedstat'), io=text(f'/proc/{pid}/io'))


def connections(port):
    rows = []
    # /proc/net/tcp encodes 127.0.0.1 as 0100007F on this little-endian host.
    for line in Path('/proc/net/tcp').read_text().splitlines()[1:]:
        _, local, remote, state, queues, *_ = line.split()
        ip, local_port = local.split(':')
        peer, remote_port = remote.split(':')
        local_port, remote_port = int(local_port, 16), int(remote_port, 16)
        if port not in (local_port, remote_port) or state != '01':
            continue
        if ip != '0100007F' or peer != '0100007F':
            continue
        tx, rx = (int(value, 16) for value in queues.split(':'))
        rows.append([local_port, remote_port, tx, rx])
    return rows


class HostSampler:
    def __init__(self, server_pid, client_pid, port, output, window):
        self.stop = threading.Event()
        self.stream = (output / 'host-diagnostics.ndjson').open('x')
        self.thread = threading.Thread(target=self.run, args=(server_pid, client_pid, port, window), daemon=True)
        self.thread.start()

    def run(self, server_pid, client_pid, port, window):
        with self.stream as stream:
            while not self.stop.is_set():
                clocks = dict(nano=time.monotonic_ns(), wallNs=time.time_ns(),
                              bootNs=time.clock_gettime_ns(time.CLOCK_BOOTTIME))
                try:
                    row = dict(server=process(server_pid), client=process(client_pid), sockets=connections(port),
                               window=text(window), cpuPressure=text('/proc/pressure/cpu'),
                               ioPressure=text('/proc/pressure/io'), memoryPressure=text('/proc/pressure/memory'))
                except (OSError, ValueError, IndexError) as error:
                    row = dict(error=repr(error))
                stream.write(json.dumps(clocks | row) + '\n')
                stream.flush()
                self.stop.wait(.25)

    def close(self):
        self.stop.set()
        self.thread.join(timeout=5)
