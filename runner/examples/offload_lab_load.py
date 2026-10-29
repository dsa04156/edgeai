"""Offload test console CPU load: busy-loops N worker processes for a bounded number of seconds, then exits.

Only for creating real node CPU pressure on one chosen edge node; it computes nothing useful and is not a benchmark.
"""
import argparse
import multiprocessing
import signal
import sys
import time


def burn(deadline):
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    value = 0
    while time.monotonic() < deadline:
        for i in range(10000):
            value = (value * 31 + i) % 1000003


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, required=True)
    parser.add_argument("--seconds", type=int, required=True)
    args = parser.parse_args(argv)
    if not 1 <= args.workers <= 16 or not 1 <= args.seconds <= 1800:
        parser.error("workers 1..16, seconds 1..1800")
    deadline = time.monotonic() + args.seconds
    workers = [multiprocessing.Process(target=burn, args=(deadline,)) for _ in range(args.workers)]

    def stop(*_):
        for worker in workers:
            worker.terminate()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()


if __name__ == "__main__":
    main()
