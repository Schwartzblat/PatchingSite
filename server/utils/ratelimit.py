import ipaddress
import threading
import time
import weakref
from collections import defaultdict, deque

from starlette import status
from starlette.exceptions import HTTPException
from starlette.requests import Request

import config
from utils.logging_config import logger

# Stop the keyspace growing without bound if we are scanned; entries older than
# the window are dead weight anyway.
MAX_TRACKED_KEYS = 20_000

# Every limiter ever constructed, so tests can clear them all between cases
# without listing each module singleton by name. Weak, so the throwaway limiters
# tests build for themselves are not kept alive by it.
_ALL_LIMITERS = weakref.WeakSet()


def client_ip(request: Request) -> str:
    """The address we rate limit on.

    In production the app sits behind exactly one trusted proxy (nginx), so we key
    on the address that proxy observed - never on anything the client can dictate:

    - ``X-Real-IP`` is set by nginx to ``$remote_addr`` with ``proxy_set_header``,
      which OVERWRITES any value the client sent, so it is trustworthy.
    - ``X-Forwarded-For`` is ``$proxy_add_x_forwarded_for``: the client's own header
      with the real peer APPENDED on the right. So its LAST entry is the hop nginx
      added and everything before it is attacker-supplied.

    We therefore read ``X-Real-IP`` first and fall back to the RIGHTMOST
    ``X-Forwarded-For`` entry. Reading the leftmost - as this once did - let a client
    prepend a unique fake hop per request and mint a fresh bucket every time, which
    defeated the login/register limiter entirely.

    Outside production there is no trusted proxy, so both headers are attacker-
    supplied and ignored in favour of the socket address.
    """
    if config.IS_PRODUCTION:
        real_ip = request.headers.get("x-real-ip", "").strip()
        if real_ip:
            return real_ip
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            # Rightmost hop: the address nginx appended, not the client's prefix.
            return forwarded.rsplit(",", 1)[-1].strip()
    return request.client.host if request.client else "unknown"


def rate_limit_key(ip: str) -> str:
    """The bucket an address counts against: itself for IPv4, its /64 for IPv6.

    An IPv6 customer is routinely handed a whole /64 and picks interface ids
    freely, so keying on the full address let one client mint a fresh bucket
    per request. An IPv4-mapped address counts as the IPv4 address it wraps.
    Anything unparseable ("unknown") is its own key.
    """
    try:
        address = ipaddress.ip_address(ip)
    except ValueError:
        return ip
    if address.version == 6:
        if address.ipv4_mapped is not None:
            return str(address.ipv4_mapped)
        return str(ipaddress.ip_network(f"{address}/64", strict=False))
    return str(address)


class RateLimiter:
    """In-process sliding window. The app runs as a single instance, so a dict
    behind a lock is enough - but it is hit from the threadpool, hence the lock."""

    def __init__(self, name: str, limit: int, window_seconds: int):
        self.name = name
        self.limit = limit
        self.window_seconds = window_seconds
        self._hits = defaultdict(deque)
        self._lock = threading.Lock()
        _ALL_LIMITERS.add(self)

    def retry_after(self, key: str) -> int:
        """Record an attempt. Returns 0 when allowed, else seconds to wait."""
        now = time.monotonic()
        cutoff = now - self.window_seconds
        with self._lock:
            hits = self._hits[key]
            while hits and hits[0] <= cutoff:
                hits.popleft()
            if len(hits) >= self.limit:
                return max(1, int(hits[0] + self.window_seconds - now) + 1)
            hits.append(now)
            if len(self._hits) > MAX_TRACKED_KEYS:
                self._evict(cutoff)
            return 0

    def reset(self, key: str = None) -> None:
        with self._lock:
            if key is None:
                self._hits.clear()
            else:
                self._hits.pop(key, None)

    def _evict(self, cutoff: float) -> None:
        stale = [key for key, hits in self._hits.items() if not hits or hits[-1] <= cutoff]
        for key in stale:
            del self._hits[key]

    def __call__(self, request: Request) -> None:
        key = rate_limit_key(client_ip(request))
        wait = self.retry_after(key)
        if wait:
            logger.warning('Rate limit reached on %s from %s', self.name, key)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Too many requests, please try again later.",
                headers={"Retry-After": str(wait)},
            )


login_rate_limit = RateLimiter("login", limit=10, window_seconds=5 * 60)
register_rate_limit = RateLimiter("register", limit=5, window_seconds=60 * 60)
# Each forgot-password call can send a mail from the site's own account, so it
# is held well below what a person retrying a typo would ever need.
forgot_password_rate_limit = RateLimiter("forgot-password", limit=5, window_seconds=60 * 60)
reset_password_rate_limit = RateLimiter("reset-password", limit=10, window_seconds=60 * 60)
# Needs a session, but each call checks the current password, so a stolen
# session must not become a faster way to guess it than /login.
change_password_rate_limit = RateLimiter("change-password", limit=10, window_seconds=60 * 60)
# Keyed on the destination address, not the caller, and consulted directly
# rather than as a dependency: past it the endpoint still answers 200 but sends
# nothing. Per-IP limits alone would let many IPs flood one inbox and get the
# sending Gmail account throttled; a 429 here would reveal the address has an
# account, which the endpoint otherwise never does.
reset_mail_per_address_limit = RateLimiter("reset-mail-per-address", limit=3, window_seconds=60 * 60)
# Anonymous, and each one is a row plus a mail to the support inbox.
bug_report_rate_limit = RateLimiter("bug-report", limit=5, window_seconds=60 * 60)
# Public: each call is a JWT check and at most one UPDATE. Room for a user who
# opens the link on a couple of devices and retries, not for guessing tokens.
verify_email_rate_limit = RateLimiter("verify-email", limit=20, window_seconds=60 * 60)
# Each resend is a mail from the site's own account. The per-account limit is
# the one that matters: registration does not prove the address, so an account
# created on someone else's address, driven from many IPs, would otherwise aim
# a steady stream of mail at that inbox. It is consulted by the endpoint, keyed
# on the username, and answers a plain 429 - the caller is signed in to the
# account, so there is nothing to hide from them.
resend_verification_rate_limit = RateLimiter("resend-verification", limit=10, window_seconds=60 * 60)
resend_verification_per_account_limit = RateLimiter(
    "resend-verification-per-account", limit=3, window_seconds=60 * 60)

# Site-wide daily mail budgets, consulted with the single key GLOBAL_KEY. Every
# mail is a login to the site's one sending account, and Gmail stops a
# consumer account after ~500 messages a day - which would silently switch
# password reset off. Per-IP limits cannot prevent that against many IPs. The
# two budgets are separate so a flood of bug reports cannot starve resets;
# together they stay under Gmail's cap. Past a budget nothing fails: resets
# answer the same 200 (and are logged), bug reports are stored unmailed.
#
# Verification mails spend the reset budget rather than a third one: both are
# mail a stranger can make the site send to an address, and a third budget
# would have to come out of the same ~500. Past it a signup still succeeds
# with verification_sent false, and a resend answers 503.
GLOBAL_KEY = "global"
reset_mail_budget = RateLimiter("reset-mail-budget", limit=300, window_seconds=24 * 60 * 60)
bug_report_mail_budget = RateLimiter("bug-report-mail-budget", limit=100, window_seconds=24 * 60 * 60)
# Patched apps call is_device_allowed on every launch, and a mobile carrier's
# NAT can put hundreds of subscribers behind one address, so this is set to
# stop a flood, not to meter normal use. Keep it generous: the patched runtime
# (Paywall.java) kills the app on ANY non-200, this 429 included, so a false
# positive closes a paying user's app on launch. Their next launch retries.
device_check_rate_limit = RateLimiter("is_device_allowed", limit=60, window_seconds=60)
# Each call decrypts the user's signing key. The phone fetches it once and
# keeps it, so this is room for reinstalls and retries, not for polling.
# Keyed per account by the endpoint (after auth), so a carrier NAT or an
# unauthenticated client cannot use up anyone else's quota.
signing_key_rate_limit = RateLimiter("signing-key", limit=20, window_seconds=60 * 60)
# Per-IP flood guard only; generous because many users can share one address.
signing_key_ip_rate_limit = RateLimiter("signing-key-ip", limit=600, window_seconds=60 * 60)
# Store read endpoints (latest / artifacts / bundle). Keyed per account by the
# endpoints after auth, so users behind one carrier NAT do not share a budget;
# the per-IP guard only stops a flood.
patch_rate_limit = RateLimiter("patch-read", limit=120, window_seconds=60)
patch_ip_rate_limit = RateLimiter("patch-read-ip", limit=1200, window_seconds=60)


def reset_all() -> None:
    """Forget every hit on every limiter. For tests."""
    for limiter in list(_ALL_LIMITERS):
        limiter.reset()
