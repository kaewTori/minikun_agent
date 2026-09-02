#!/usr/bin/env python3
"""Restart boundary for TinyGrad GPU and compiler-process faults.

The child keeps owning the HTTP port. This supervisor only mirrors stderr and
returns a failure code when TinyGrad reports an unrecoverable NV state, letting
launchd's KeepAlive policy start a clean process.
"""

from __future__ import annotations

import signal
import subprocess
import sys
from typing import Sequence


FAULT_MARKERS = (
    b"device fault detected",
    b"memoryerror: allocation",
)
# Broken pipes are normal client disconnects (for example an expired health
# probe) and must never be treated as evidence that the NV runtime is corrupt.
TAIL_BYTES = 4_096


def supervise(command: Sequence[str]) -> int:
    if not command:
        print("SDXL supervisor requires a child command", file=sys.stderr, flush=True)
        return 64

    child = subprocess.Popen(command, stderr=subprocess.PIPE)

    def stop_child(_signum: int, _frame: object) -> None:
        if child.poll() is None:
            child.terminate()

    signal.signal(signal.SIGTERM, stop_child)
    signal.signal(signal.SIGINT, stop_child)

    assert child.stderr is not None
    tail = b""
    fault_detected = False
    while True:
        chunk = child.stderr.read1(4_096)
        if not chunk:
            break
        sys.stderr.buffer.write(chunk)
        sys.stderr.buffer.flush()
        tail = (tail + chunk.lower())[-TAIL_BYTES:]
        if any(marker in tail for marker in FAULT_MARKERS):
            fault_detected = True
            print(
                "SDXL supervisor detected an unrecoverable runtime fault; "
                "terminating so launchd can restart the service",
                file=sys.stderr,
                flush=True,
            )
            if child.poll() is None:
                child.terminate()
            break

    if fault_detected:
        try:
            child.wait(timeout=10)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
        return 86
    return child.wait()


if __name__ == "__main__":
    raise SystemExit(supervise(sys.argv[1:]))
