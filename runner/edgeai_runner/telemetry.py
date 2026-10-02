"""Read this process's cgroup and optional workload measurements; absent is never zero.

Path/clock injection is solely for deterministic reader tests. Production resolves its own
cgroup membership; it never silently substitutes host-root usage for an unavailable group.
"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import stat
import time

MAX_INTEGER = 9007199254740991


def utc_now():
    return datetime.now(timezone.utc)


def own_cgroup():
    try:
        member = next(line[3:] for line in Path('/proc/self/cgroup').read_text().splitlines() if line.startswith('0::'))
        root = Path('/sys/fs/cgroup').resolve(strict=True)
        path = (root / member.lstrip('/')).resolve(strict=True)
        return path if path.is_relative_to(root) and (path / 'cgroup.controllers').is_file() else None
    except (OSError, StopIteration, ValueError):
        return None


def bounded(value, maximum=MAX_INTEGER, minimum=0):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError('Invalid metric')
    return value


def unique_pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError('Duplicate metric field')
        result[key] = value
    return result


class Sampler:
    def __init__(self, work, cgroup=None, monotonic=time.monotonic, now=utc_now):
        self.work = work
        self.cgroup = cgroup
        self.monotonic, self.now = monotonic, now
        self.previous_time = monotonic()
        self.previous_cpu = self.cpu()
        self.latency_sequence = 0
        self.sequence = 0

    def cpu(self):
        if self.cgroup is None:
            return None
        try:
            fields = dict(line.split() for line in (self.cgroup / 'cpu.stat').read_text().splitlines())
            return bounded(int(fields['usage_usec']))
        except (OSError, ValueError, KeyError):
            return None

    def limit(self, name):
        if self.cgroup is None:
            return None
        try:
            return bounded(int((self.cgroup / name).read_text()), minimum=1)
        except (OSError, ValueError):
            return None  # 'max' denotes an unbounded quota, not zero.

    def latency(self, now):
        try:
            fd = os.open(self.work / 'telemetry.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, 'rb') as file:
                info = os.fstat(file.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > 4096:
                    return None, None
                data = file.read(4097)
            if len(data) > 4096:
                return None, None
            value = json.loads(data, object_pairs_hook=unique_pairs)
            if set(value) != {'sequence', 'observedAt', 'latencyMicros'}:
                return None, None
            sequence = bounded(value['sequence'], minimum=1)
            if sequence <= self.latency_sequence:
                return None, None
            observed = datetime.fromisoformat(value['observedAt'].replace('Z', '+00:00'))
            if observed.tzinfo is None or not -5 <= (now - observed).total_seconds() <= 30:
                return None, None
            latency = bounded(value['latencyMicros'], 600000000)
            self.latency_sequence = sequence
            return latency, observed.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
        except (OSError, ValueError, TypeError, KeyError, AttributeError, OverflowError):
            return None, None

    def sample(self):
        timestamp = self.monotonic()
        elapsed = round((timestamp - self.previous_time) * 1000)
        if elapsed < 200:
            return None
        now = self.now()
        cpu, previous = self.cpu(), self.previous_cpu
        self.previous_cpu, self.previous_time = cpu, timestamp
        usage = cpu - previous if cpu is not None and previous is not None and cpu >= previous and elapsed <= 60000 else None
        cpu_limit = memory = memory_limit = None
        if self.cgroup is not None:
            try:
                if usage is not None:
                    quota, period = (self.cgroup / 'cpu.max').read_text().split()
                    cpu_limit = bounded(int(quota) * 1000 // bounded(int(period), minimum=1), 1000000000, 1)
            except (OSError, ValueError):
                pass
            try:
                memory = bounded(int((self.cgroup / 'memory.current').read_text()))
                memory_limit = self.limit('memory.max')
            except (OSError, ValueError):
                pass
        latency, latency_at = self.latency(now)
        if elapsed > 60000 or usage is None and memory is None and latency is None:
            return None
        self.sequence += 1
        return {'sequence': self.sequence, 'observedAt': now.isoformat(timespec='microseconds').replace('+00:00', 'Z'), 'intervalMillis': elapsed,
                'cpuUsageMicros': usage, 'cpuLimitMillicores': cpu_limit, 'memoryBytes': memory, 'memoryLimitBytes': memory_limit,
                'latencyMicros': latency, 'latencyObservedAt': latency_at}
