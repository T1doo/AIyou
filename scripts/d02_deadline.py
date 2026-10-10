"""Bound the owned Linux runner's blocking archive reads without changing TLS or killing other processes."""
from contextlib import contextmanager
import math
import signal


@contextmanager
def archive_download_deadline(seconds):
    if not math.isfinite(seconds) or seconds <= 0:
        raise TimeoutError("No archive download time remains")
    delay, interval = signal.getitimer(signal.ITIMER_REAL)
    if delay or interval:
        raise RuntimeError("Refuse to replace an existing process timer")
    previous = signal.getsignal(signal.SIGALRM)
    def expired(_signal, _frame):
        raise TimeoutError("Owned archive download deadline exceeded; partial bytes retained")
    signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
