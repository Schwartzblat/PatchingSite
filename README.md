# PatchingSite

Subscription site that serves patched Android apps. FastAPI + sqlite backend, React + Vite
frontend, Stripe for billing.

Each download is re-signed on demand with a per-user token baked into
`assets/paywall.json`, so a leaked APK can be traced and its device count enforced.

## Layout

```
server/            FastAPI app
  config.py        all settings, env validation (import fails fast if misconfigured)
  database.py      sqlite access + schema migration
  manage.py        operational CLI (migration check, doctor, bug reports, account deletion)
  api/             routers, mounted under /api: users, auth, password_reset, apps,
                   payment, store, bug_reports
  utils/           jwt, passwords, stripe, mailer, logging, rate limits, downloads,
                   apk patching
  tests/           pytest
client/            React 19 + Vite SPA
  src/lib/         api client + auth context
android/           Kotlin + Compose store client (see android/README.md)
```

## Setup

Requires Python 3.12+ and Node 20+. APKs are signed with Stitch's bundled
`fastsigner` binary, so the server needs no JDK (the Android client's build still does).

```bash
cp .env.example .env      # then fill in the three required values

cd server
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m pip install -e /path/to/Stitch   # see note below
.venv/bin/python -m pytest

cd ../client
npm install
```

### The `stitch` dependency

`server/utils/apps_utils.py` imports `stitch`, which is **not on PyPI** — it is a local
sibling project (Stitch) installed in editable mode. Anyone deploying this needs a
checkout of it. Making it a git URL in `requirements.txt`, or vendoring
it, would make deployment reproducible.

## Running

```bash
cd server && .venv/bin/uvicorn main:app --reload --port 8000
cd client && npm run dev        # proxies /api to :8000 (see vite.config.js)
```

Set `ENVIRONMENT=development` in `.env` for local work. It defaults to
`production`, which issues Secure-only cookies a browser will not store over
`http://localhost`.

## Publishing the store app

The home page offers the Android client (`android/`) for download from
`/api/store/download`, and hides the offer until a build exists. The server serves
`/opt/patched/store.apk` as-is (override with `STORE_APK_PATH`), so publish a
release build signed with the shared keystore (see `.env.example`):

```bash
cd android && ./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk /opt/patched/store.apk
```

No restart is needed; the file is looked up on each request.

Installed copies of the app update themselves from this file. `GET /api/store`
reports the `version_code` inside the APK, and the app offers any build with a higher
code than its own. Release builds stamp that code from the build time, so publishing a
fresh `assembleRelease` output is all an update takes. See "How the store updates
itself" in `android/README.md`.

## Email

Signup verification links, password reset links and bug reports are sent over
SMTP with TLS, from a background thread so a slow mail server never holds a
request. Defaults suit a Gmail account with an
[app password](https://myaccount.google.com/apppasswords): only the two
credentials are required.

| Variable | Default | Purpose |
|---|---|---|
| `SMTP_USERNAME` | (unset) | SMTP login, e.g. the Gmail address. |
| `SMTP_PASSWORD` | (unset) | SMTP password (a Gmail app password, not the account password). |
| `SMTP_HOST` | `smtp.gmail.com` | |
| `SMTP_PORT` | `465` | 465 is implicit TLS; any other port is upgraded with STARTTLS. Never plaintext. |
| `MAIL_FROM` | `SMTP_USERNAME` | Sender address. Gmail rewrites any address that isn't a verified alias. |
| `SUPPORT_EMAIL` | `sudorm.ext@gmail.com` | Where bug reports go; shown to users when self-service fails. |

Without SMTP the site keeps working, degraded: `/api/auth/forgot-password`
and `/api/auth/resend-verification` answer 503 with the support address,
signups succeed with `verification_sent: false`, and bug reports are stored
but not emailed. New accounts then **cannot verify, so cannot subscribe**
until mail is back. `manage.py doctor` warns about it in production. Failures
are logged with the password scrubbed.

## Account and support endpoints

All under `/api`; errors keep FastAPI's `{"detail": "..."}` shape.

### Email verification

An unverified account can sign in and browse, but **cannot subscribe** and is
**not sent password reset links**. Registration doesn't prove an address, so
until it is proven the account may be using someone else's: a reset link would
hand the account to whoever reads that inbox, and the Stripe customer is
created with that address.

Every account that existed when this shipped is **grandfathered**: the
migration that adds `users.email_verified_at` (ISO-8601 UTC, NULL means
unverified) stamps every row present at that moment, in the same transaction.
Grandfathering is keyed on the column being added, never on its value, so
restarting or re-running `migrate` never verifies a later signup.

The state is always read from the database row, never from a session token:
tokens outlive the verification (by up to 90 days on a phone), and opening the
link must unlock checkout for a session that is already open.

- `POST /register` answers `{"message": "User registered successfully",
  "verification_sent": true|false}` and queues a link to the address. False
  means no SMTP or the day's mail budget is spent; the account is created
  either way.
- `POST /auth/verify-email` `{token}` (public, since the link is opened wherever
  the mail is read): 200 `{"message": "Your email address is verified.",
  "username": ...}`, the same when already verified. One 400 (`This
  verification link is invalid or has expired.`) for a bad, expired or foreign
  token, an unknown account, or an account whose address changed since the link
  was sent. The link is `{FRONTEND_URL}/verify-email?token=<jwt>`: a 48-hour JWT
  (`typ: email_verify`) naming the username and the address. Stateless, like
  reset links, and every other token validator refuses it (and it refuses them).
- `POST /auth/resend-verification` (cookie or bearer): 200, 409 when already
  verified, 503 naming `SUPPORT_EMAIL` when there is no SMTP or the daily
  budget is spent, 429 past 3/hour per account or 10/hour per IP.
- `GET /users/me` includes `email_verified`.
- `POST /subscription/create-checkout` answers **403** for an unverified
  account, before any Stripe call and before the 409 below.
- Completing a password reset also verifies the address, since only the inbox
  could have received the link.

### Other endpoints

- `POST /login` (web cookie) and `POST /auth/token` (phone) take the username
  **or the account's email address** in the `username` field, matched without
  regard to case. The username is tried first, and an address only counts when
  exactly one account holds it, which the unique email index guarantees on the
  live database. The session always names the username.
- `GET /subscription/price` (public): the plan's price as
  `{amount, currency, interval, interval_count}`, `amount` in minor units.
  Cached for 10 minutes. 503 when Stripe can't say, and the clients then hide
  the amount rather than show a hardcoded one.
- `GET /subscription`, the download endpoint and every billing action read the
  account's Stripe customer from `users.customer_id`, never from the session
  token, which is stamped at sign-in. An account linked later needs no new
  sign-in, and its APKs carry the linked customer. When the row names no
  customer, `GET /subscription` links one Stripe has for the account: the one
  tagged with its username, or one made in the dashboard with its verified
  address and a live subscription to this product (see "Giving someone a free
  trial"). A miss is remembered for a minute, and if Stripe is down the answer
  is "no subscription", not a 503. Only subscriptions to `STRIPE_PRODUCT_ID`
  are ever read; the other product sold from the same Stripe account is
  invisible to every check.
- `POST /subscription/create-checkout` answers **409** when the account already
  has an active subscription. If that customer was never stamped on the account
  (checkout finished, `confirm_success` never ran, or it was made in the
  dashboard), the 409 links it and refreshes the session cookie.
- `POST /auth/forgot-password` `{email}` always answers the same 200, so an
  address can't be probed, nor whether its account is verified. Only verified
  accounts are actually mailed. One mail lists every verified account on the
  address, each with its own link, since `users.email` is not unique on the
  live data. The link is a 1-hour JWT (`typ: password_reset`) bound to a digest
  of the current password hash, so it works once and dies whenever the password
  changes. No server-side state.
- `POST /auth/reset-password` `{token, password}`: the password is validated
  and Argon2-hashed exactly as at registration.
- `POST /users/me/password` `{current_password, new_password}`, signed in: the
  current password is checked (legacy hashes too), the new one gets the
  registration rules. A wrong current password is a **400**, not a 401, which
  the web client would read as an expired session; **409** means the password
  changed elsewhere meanwhile. It never verifies the address. Other sessions
  stay signed in, because session tokens aren't bound to the password, and the
  form on `/subscription` says so. A reset link mailed earlier dies with the
  old hash.
- `GET /users/me/devices` and `POST /users/me/devices/reset`: users clear
  their own device slots, once every `DEVICE_RESET_INTERVAL_DAYS` (30). The
  device-limit 403 from `is_device_allowed` now names `<site>/subscription`,
  but the patched apps' runtime (`Paywall.java`) never reads the body: on
  any non-200 it just closes the app. Users find the reset through the site
  and the store app, not through the patched app.
- `POST /bug-reports` (auth optional): stored in `bug_reports` first, then
  mailed to `SUPPORT_EMAIL` with Reply-To set to the reporter.
- `/admin/...`: the admin panel's API (`server/api/admin.py`). It needs the
  session **cookie** of an account in `ADMIN_USERNAMES`: 401 without one (a
  bearer token alone is not enough), 403 for anyone else. The session must
  come from the web sign-in: a phone's token is refused even if it is sent as
  a cookie. `GET /users/me` reports `is_admin`. See "The admin panel" under
  "Operations".

Per-IP rate limits: forgot-password 5/hour, reset-password 10/hour,
change-password 10/hour, verify-email 20/hour, resend-verification 10/hour,
bug reports 5/hour, `is_device_allowed` 60/minute. That last one is generous on
purpose: patched apps call it on every launch, often from behind a carrier
NAT, and they close on any non-200, a 429 included. IPv6 clients are
limited per /64 prefix, not per address. Reset mails are also capped at
3/hour per address, silently, so many IPs cannot flood one inbox.

Mail has site-wide daily budgets too: 300 reset and verification mails
(shared), and 100 bug-report mails. Together they stay under Gmail's
~500/day, since an account over its quota would silently switch password
reset off. Past a budget nothing fails: resets still answer 200 and log an
error, signups succeed with `verification_sent: false`, a resend answers 503,
and bug reports are stored but not mailed (`manage.py bug-reports` shows them
as `NOT MAILED`).

Downloads (`/downloads/{id}/file`, `/store/download`) honour `Range`, so the
Android client resumes interrupted transfers.

## On-device patching endpoints

The patcher can run on the phone instead of the server. A crawler publishes one
**record** per supported app build and a signed **bundle** per patcher version;
the store app reads them, fetches its signing key and builds the patched APK
locally. All under `/api`; errors keep the `{"detail": "..."}` shape.

### Internal endpoints (crawler and bundle uploader)

Both need the header `X-Internal-Token`, compared in constant time: **401** when
it is missing or wrong, **503** `Not configured` when the server has no secret.

- `POST /internal/records` (token = `CRAWLER_SECRET`): a JSON object with
  exactly `package, version_name, version_code (int), apk_sha256, dex_sha256,
  patcher_version, artifacts (string to string), missing_keys (array),
  features_off (array), supported (bool), source` (e.g. `"whatsapp.com"`). Extra
  or mistyped fields are 422, an unknown package 404. Answers `{"revision": n}`.
  It is an upsert by (package, `dex_sha256`, `patcher_version`): resending an
  identical record keeps its revision; a changed one gets a new one.
- `POST /internal/bundles` (token = `BUNDLE_UPLOAD_SECRET`): the **raw zip** as
  the request body, at most 16 MiB (413 above that; nginx allows 17m on this
  exact path only). The zip is verified against the key at
  `BUNDLE_PUBLIC_KEY_PATH` (503 when that file is missing, 400 when the bundle
  fails verification or its metadata is wrong). It is stored content-addressed
  as `BUNDLES_DIR/<pkg>/<ver>-<sha16>.zip`, older content for the same version
  is removed, and the answer is `{"package", "patcher_version", "revision"}`.

### Store endpoints

These need a signed-in account with an active subscription (401/402 otherwise).

- `GET /patch/{pkg}/latest`: the newest supported build. The optional header
  `X-Store-Version-Code` (integer; absent or invalid counts as 0) is the store
  app's own version, so it is only offered builds whose bundle it can run. Answers
  `package, version_code, version_name, apk_url, apk_sha256, dex_sha256,
  patcher_version, revision, features_off`; 404 `No supported version yet`.
- `GET /patch/{pkg}/artifacts?patcher_version=...&apk_sha256=...` (or
  `dex_sha256=...`; exactly one of the two, else 422): the full record,
  including `artifacts`, for a build the phone already has. 404 `Unknown build`.
- `GET /patch/{pkg}/bundle/{patcher_version}`: the bundle zip, with its SHA-256
  as the `ETag`. 404 `Unknown bundle`.
- `POST /app-token` `{"package": ...}`: the same paywall token a server-patched
  APK is built with, as `{"token": ...}`.
- `GET /signing-key` (signed in; no subscription needed): the account's own
  signing key, created on first use, as `{pkcs12 (base64), password, alias,
  cert_sha256, cert_sha1}` with `Cache-Control: no-store`. 503 without
  `SIGNING_KEYS_MASTER_KEY`.

`patcher_version` must match `^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$` (422 otherwise);
packages must be in the app catalogue (404 otherwise). `GET /apps` reports
`on_device` for each app.

Rate limits: each endpoint is limited **per account after authentication** and
by a per-IP flood guard in front of it. `/patch/...` reads 120/minute per
account and 1200/minute per IP; `/app-token` 60/hour per account and 600/hour per
IP; `/signing-key` 20/hour per account and 600/hour per IP. Past a limit the
answer is 429 with `Retry-After`.

### Settings

| Variable | Meaning |
| --- | --- |
| `CRAWLER_SECRET` | token for `POST /internal/records` |
| `BUNDLE_UPLOAD_SECRET` | token for `POST /internal/bundles` |
| `SIGNING_KEYS_MASTER_KEY` | base64 of 32 random bytes; encrypts the stored signing keys |
| `BUNDLE_PUBLIC_KEY_PATH` | public key the bundles are verified against (default `DATA_DIR/bundle-signing.pub.pem`) |
| `BUNDLES_DIR` | where bundles are stored (default `DATA_DIR/bundles`) |
| `ON_DEVICE_PACKAGES` | comma-separated packages switched to on-device patching (default empty) |

None is needed to start; an endpoint whose setting is missing answers 503.
`manage.py doctor` warns about each unset one (and about a missing public key),
and fails only when `BUNDLES_DIR` exists but cannot be written. Make a master key
with:

```bash
python -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())"
```

**Losing `SIGNING_KEYS_MASTER_KEY` makes every stored signing key unreadable**,
and users' patched apps would then be signed with new keys. Back it up
separately from the database: a backup of both together protects nothing, and
the database alone is useless without it. Don't rotate it.

### Launch day

Deploying this changes nothing for current subscribers: `ON_DEVICE_PACKAGES`
is empty. Once the crawler has published records and a bundle for WhatsApp,
set `ON_DEVICE_PACKAGES=com.whatsapp` in `.env` and restart the service.

## Operations

```bash
cd server
.venv/bin/python manage.py check                    # read-only: will the migration apply cleanly?
.venv/bin/python manage.py migrate                  # apply it (idempotent, additive)
.venv/bin/python manage.py doctor                   # validate configuration for production
.venv/bin/python manage.py bug-reports --limit 20   # read-only: recent bug reports, newest first
.venv/bin/python manage.py delete-user <name>       # delete an account on request (asks first)
.venv/bin/python manage.py grant-trial <name> --days 30   # a free trial on Stripe (asks first)
```

The migration also runs at startup (`database.init()`), so a restart applies it.
It is additive: the `users.devices_reset_at` and `users.email_verified_at`
columns and the `bug_reports` table. Adding `email_verified_at` also marks
every account present at that moment as verified (see "Email verification");
`check` says how many that will be, and afterwards how many are verified and
unverified. `deploy/deploy.sh` backs the database up first whenever
`database.py` changed.

`doctor` runs as the unit's `ExecStartPre`, so only real misconfiguration fails
it. Missing SMTP and a derived `APP_JWT_SECRET_KEY` are **warnings**. The
second matters before you ever rotate `JWT_SECRET_KEY`: the app-token key is
derived from it when unset, so rotating it would silently invalidate every APK
already handed out. Pin the current value once, on the host, before anything
else changes `JWT_SECRET_KEY`. The leading newline stops the value being glued
onto a last line that has none:

```bash
cd server
.venv/bin/python -c 'import config; print("\nAPP_JWT_SECRET_KEY=" + config.APP_JWT_SECRET_KEY)' >> ../.env
```

`bug-reports` exists because the host has no sqlite3 CLI. It also shows
reports whose mail never arrived (`NOT MAILED`).

`delete-user` removes the account and unlinks its bug reports: username,
reply address and IP are cleared and the text is kept. It prints the Stripe
customer id; cancel and delete that customer in the dashboard yourself.

### The admin panel

`/admin` on the site lists every account (search by username or email). For
the one you open it shows the Stripe subscription of its linked customer and
offers these actions:

- change the email (unverified unless you tick that it is verified);
- mark the email verified;
- clear the device slots, without using up the user's own 30-day reset;
- grant a free trial;
- delete the account.

The trial and the delete follow the same rules as `grant-trial` and
`delete-user`, because all of them call the same code (`utils/trials.py`,
`database.delete_user`). Opening an account never links a Stripe customer to
it, and the Stripe customer's own email is never changed.

Only accounts named in `ADMIN_USERNAMES` can open it, and only with the web
session cookie. **Register the account first, then list it and restart.**
`/register` refuses a listed name, so that nobody can claim one before you do,
and `doctor` warns about a listed name with no account. Usernames can't be
renamed, because every APK's token carries one. Each change is logged in
`app.log` as `Admin <you> ...`.

The panel needs a **web** session. Session tokens carry a `client` claim
("web" from `/login`, "mobile" from `/auth/token` and `/auth/renew`), and the
panel refuses anything but "web", so a phone's 90-day token is no use even
when sent as a cookie. Cookies minted before the claim existed have none, so
after deploying this, sign in again once. Ordinary users and installed phones
are unaffected.

Open `/admin` in a browser where Google Analytics was declined (or decline it
there). If accepted, GA4's outbound-click tracking would send the "Open in
Stripe" link, which contains the customer id.

Other policy knobs, all optional:

| Variable | Default | Purpose |
|---|---|---|
| `DEVICE_RESET_INTERVAL_DAYS` | `30` | How often users may clear their device slots. |
| `DOWNLOAD_MAX_JOBS_PER_USER` | `2` | Queued + running download jobs per account, so one user can't fill the queue. |
| `LEGACY_APP_TOKEN_CUTOFF` | (unset) | ISO date (UTC). Once it has passed, APKs carrying the old no-expiry token get a 401 and must be downloaded again. Unset keeps accepting them. A malformed value stops the server from starting. |
| `ADMIN_USERNAMES` | (unset) | Comma-separated usernames that may use the admin panel at `/admin`. Unset turns it off. Register the account before listing it. |

`deploy/` holds a systemd unit template, the nginx config and `deploy.sh`, which
deploys whatever changed since the last deploy (`--dry-run` shows the plan). It
reads the target host from `deploy/deploy.env`; copy `deploy/deploy.env.example`
and fill it in. The service must run a **single** uvicorn worker: the download job
registry, the rate limiters and the subscription status cache are all
in-process.

### Giving someone a free trial

Every way, the account's email address must be verified (grandfathered
accounts count as verified). The site grants access while the subscription
is `active` or `trialing`, and not in any other state.

Only subscriptions to this site's product (`STRIPE_PRODUCT_ID`) count. The
same Stripe account sells another product, and its subscriptions never grant
access here. They never make one of its customers an account's, and they
never trigger checkout's "already subscribed" refusal or grant-trial's.

**In the admin panel**, open the account at `/admin`, choose the days or the
end date under "Free trial" and press Preview. It shows what `grant-trial`'s
summary shows, including a red warning when a saved card will be charged at
the end. Grant trial then creates it, with the same rules and the same
refusals, and writes `granted_by: "admin panel (<you>)"` into the
subscription's metadata (`grant-trial` writes `manage.py grant-trial`).

**With `manage.py grant-trial`**, on the host:

```bash
cd server
.venv/bin/python manage.py grant-trial <name> --until 2026-12-31   # ends at 00:00 UTC that day
.venv/bin/python manage.py grant-trial <name> --days 30            # ends at 00:00 UTC, 30 days from today
```

It puts a trialing subscription on the product's default price onto the
account's own Stripe customer: the one already linked, else the one the site
would link (see below), else a new one created with the account's email. A
customer of the other product is never reused just because it has the same
address; a new one is created instead. It
links that customer to the account, which sees the trial at once. Installed
patched apps can take up to a minute. It prints a summary and asks first
(`--yes` skips the question). It refuses an unverified address, a customer
that already has an active or trialing subscription, and an end date that has
passed or is more than 730 days away (Stripe's limit).

The trial is created with "cancel if there is no payment method". If no card
has been added by the end, the subscription is **cancelled**: nothing is
charged, nothing goes `past_due`, and the account loses access. If a card has
been added, it turns into a normal paid subscription. The summary warns you
when the customer already has a saved card, because that card will be charged
when the trial ends.

**In the Stripe dashboard**, create a customer with exactly the account's
email address (case doesn't matter) and give it a subscription **to this
site's product** with a trial. No metadata is needed. The account is linked
the next time it opens the web Subscription page or the store app's Plan tab,
or starts a download, within about a minute of the customer existing. It links
by email only when:

- the account's address is verified;
- the customer has an `active` or `trialing` subscription to this site's
  product (an address alone could belong to the other product's customer);
- the customer has no `username` in its metadata, or has this account's;
- the customer isn't already linked to another account;
- no other verified account uses the same address.

The site then stamps `metadata.username` on the customer, unless the customer
also has a subscription to another product: the other product's backend may
read that key, so such a customer is linked in `users.customer_id` only. A
tag naming another username is never overwritten. A customer tagged with the
account's username is linked once it holds any subscription to this product.

If several customers qualify, one with a live subscription to this product
wins, then the newest. An address that differs only in case is found through
Stripe's search, which can lag a minute behind. The customer already linked
to an account (`users.customer_id`) always wins over any other. For an account
that has subscribed before, add the trial to its existing customer rather than
creating a second one (grant-trial does that for you).

What happens at the end of a dashboard trial with no card depends on that
subscription's own setting for a missing payment method. **Cancel** behaves
like grant-trial. Stripe's API default instead creates an invoice, which
leaves the subscription `past_due` with an unpaid invoice.

## Notes for future work

- Subscription state is read from the Stripe API; `is_device_allowed` caches
  it per customer for 60 seconds, and every endpoint that can change it drops
  the entry. A `checkout.session.completed` webhook would remove the
  dependency on the client POSTing `confirm_success` after checkout, and let
  the status be cached locally for longer.
- Jobs and rate-limit counters live in the app process, so this runs as a **single
  instance**. Running more than one replica needs Redis (or equivalent) behind both.
- sqlite runs in WAL mode. It is fine at this scale; a move to Postgres would be driven
  by concurrent writes, not read volume.
