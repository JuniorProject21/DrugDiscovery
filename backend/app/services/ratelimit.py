"""In-memory rate limits for the sign-in endpoints.

Counts are kept per server process and reset on restart, which is enough to stop password
guessing against a single-instance deployment.
"""

import math
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field


class RateLimiter:
    """At most `limit` attempts per key within any `window_seconds`."""

    def __init__(self, limit: int, window_seconds: float):
        self.limit = limit
        self.window = window_seconds
        self._attempts: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        """Record an attempt for `key`. False when the key has used up its limit."""
        now = time.monotonic()
        with self._lock:
            attempts = self._attempts[key]
            self._expire(attempts, now)
            if len(attempts) >= self.limit:
                return False
            attempts.append(now)
            if len(self._attempts) > 10_000:
                self._prune(now)
            return True

    def retry_after(self, key: str) -> int:
        """Seconds until `key` may try again."""
        with self._lock:
            attempts = self._attempts.get(key)
            if not attempts:
                return 0
            return max(1, math.ceil(self.window - (time.monotonic() - attempts[0])))

    def _expire(self, attempts: deque[float], now: float) -> None:
        while attempts and now - attempts[0] >= self.window:
            attempts.popleft()

    def _prune(self, now: float) -> None:
        for key in list(self._attempts):
            self._expire(self._attempts[key], now)
            if not self._attempts[key]:
                del self._attempts[key]


@dataclass
class AuthRateLimits:
    login_per_ip: RateLimiter = field(default_factory=lambda: RateLimiter(30, 15 * 60))
    login_per_email: RateLimiter = field(default_factory=lambda: RateLimiter(10, 15 * 60))
    signup_per_ip: RateLimiter = field(default_factory=lambda: RateLimiter(10, 60 * 60))
    google_per_ip: RateLimiter = field(default_factory=lambda: RateLimiter(30, 15 * 60))
    reset_request_per_ip: RateLimiter = field(default_factory=lambda: RateLimiter(10, 60 * 60))
    reset_request_per_email: RateLimiter = field(default_factory=lambda: RateLimiter(3, 60 * 60))
    reset_confirm_per_ip: RateLimiter = field(default_factory=lambda: RateLimiter(20, 15 * 60))
