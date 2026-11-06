"""Atomic verification admission and bounded password memory under contention."""
from concurrent.futures import ThreadPoolExecutor
import threading
import unittest
from unittest.mock import Mock, patch

import simhub_server as server


class VerificationAdmissionTests(unittest.TestCase):
    def setUp(self):
        server._verification_attempts.clear()

    def tearDown(self):
        server._verification_attempts.clear()

    def test_concurrent_attempts_reserve_before_verification(self):
        start=threading.Barrier(17)
        def attempt(_):
            start.wait(timeout=5)
            return server.verification_allowed("stepup","test-ip","test-session")
        with ThreadPoolExecutor(max_workers=16) as pool:
            pending=[pool.submit(attempt,i) for i in range(16)]
            start.wait(timeout=5)
            self.assertEqual(sum(task.result(timeout=5) for task in pending),5)

    def test_both_session_and_ip_are_bounded_and_login_is_independent(self):
        for index in range(5):
            self.assertTrue(server.verification_allowed("stepup",f"ip-{index}","shared-session"))
        server.verification_success("login","ip-0","shared-session")
        self.assertFalse(server.verification_allowed("stepup","new-ip","shared-session"))
        for index in range(5):
            self.assertTrue(server.verification_allowed("stepup","shared-ip",f"session-{index}"))
        self.assertFalse(server.verification_allowed("stepup","shared-ip","new-session"))
        self.assertTrue(server.verification_allowed("login","shared-ip"))

    def test_only_matching_verified_factor_clears_budget_and_window_expires(self):
        with patch.object(server,"now",return_value=1000):
            for _ in range(5):
                self.assertTrue(server.verification_allowed("stepup","ip","session"))
            server.verification_success("login","ip","session")
            self.assertFalse(server.verification_allowed("stepup","ip","session"))
            server.verification_success("stepup","ip","session")
            self.assertTrue(server.verification_allowed("stepup","ip","session"))
        with patch.object(server,"now",return_value=1061):
            self.assertTrue(server.verification_allowed("stepup","ip","session"))


class PasswordCapacityTests(unittest.TestCase):
    def test_password_verifications_do_not_exceed_memory_slots(self):
        entered=threading.Barrier(3)
        release=threading.Event()
        def expensive_verify(*_):
            entered.wait(timeout=5)
            if not release.wait(timeout=5):
                raise TimeoutError("Test worker not released")
            return True
        hasher=Mock(verify=Mock(side_effect=expensive_verify))
        with patch.object(server,"admin_password_hash",return_value="test-hash"), \
             patch.object(server,"_password_hasher",hasher), \
             patch.object(server,"_password_slots",threading.BoundedSemaphore(2)):
            with ThreadPoolExecutor(max_workers=2) as pool:
                workers=[pool.submit(server.verify_admin_password,"test-password") for _ in range(2)]
                try:
                    entered.wait(timeout=5)
                    with self.assertRaises(server.AuthenticationBusy):
                        server.verify_admin_password("third-password")
                    self.assertEqual(hasher.verify.call_count,2)
                finally:
                    release.set()
                self.assertTrue(all(worker.result(timeout=5) for worker in workers))

    def test_password_capacity_is_released_on_exception(self):
        with patch.object(server,"_password_slots",threading.BoundedSemaphore(1)):
            with self.assertRaises(ValueError):
                with server.password_work():
                    raise ValueError("synthetic failure")
            with server.password_work():
                pass


if __name__=="__main__":unittest.main()
