import importlib.util
from pathlib import Path
import signal
import time
import unittest

spec = importlib.util.spec_from_file_location("d02_deadline", Path(__file__).resolve().parents[1] / "d02_deadline.py")
deadline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deadline)


class OwnedDownloadDeadlineTest(unittest.TestCase):
    def test_interrupts_blocking_operation_and_restores_timer_and_handler(self):
        old = signal.getsignal(signal.SIGALRM)
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            with deadline.archive_download_deadline(0.02):
                time.sleep(2)
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))
        self.assertEqual(old, signal.getsignal(signal.SIGALRM))

    def test_existing_timer_is_retained_and_product_exception_is_not_replaced(self):
        expected = ValueError("synthetic transfer exception")
        with self.assertRaises(ValueError) as failure:
            with deadline.archive_download_deadline(10):
                before = signal.getitimer(signal.ITIMER_REAL)[0]
                with self.assertRaises(RuntimeError):
                    with deadline.archive_download_deadline(1):
                        self.fail("must not enter")
                self.assertLessEqual(signal.getitimer(signal.ITIMER_REAL)[0], before)
                self.assertGreater(signal.getitimer(signal.ITIMER_REAL)[0], 0)
                raise expected
        self.assertIs(expected, failure.exception)
        self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))

    def test_no_budget_or_nonfinite_budget_does_not_start_a_timer(self):
        for value in (0, -1, float("inf"), float("nan")):
            with self.subTest(value=value), self.assertRaises(TimeoutError):
                with deadline.archive_download_deadline(value):
                    self.fail("must not enter")
        self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))
