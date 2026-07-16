# Three Scenarios

**Project:** URL Shortener
**Author:** Jayanth Reddy Aeradla
**Date:** 15 July 2026

The assignment asks for three scenarios — greenfield, brownfield, and ambiguous — each showing how I broke the work down, executed it, and validated it. I didn't stage these. They came out of the order I built things, and the evidence is in the git history and the test suite.

---

## Scenario 1: Greenfield

### The requirement

"Build a URL shortener service from scratch with core APIs."

Nothing existed. Empty project, one sentence of requirement.

### How I broke it down

I worked bottom-up, because each layer needs the one below it to exist first:

1. Database table
2. Java class that maps to it
3. Repository to read and write it
4. Short code generation
5. Business rules (validation, collision handling, expiry)
6. HTTP endpoints
7. Error handling

Seven steps. Each one runnable before I started the next. I didn't move on until the app booted clean.

### The work

Three decisions here were not obvious, and they're the ones I'd defend.

**Random codes, not counting.**

The easy way is to auto-increment an ID and Base62 it. Link 1 gets `1`, link 125 gets `21`. No collisions ever, shortest possible codes, no retry logic. It's what most people reach for.

It's also disqualifying here. Sequential codes are guessable. Anyone can walk `/r/1`, `/r/2`, `/r/3` and harvest every link in the system. At a brokerage that could mean internal documents. It leaks volume too — make one link, read the number, and you know how many links exist.

So I went random. Seven Base62 characters, 3.5 trillion possibilities. At 10 million links, the chance any new code hits an existing one is roughly 1 in 350,000. That's a real cost — collisions are possible now — but it buys a security property I can't retrofit later.

I used `SecureRandom`, not `java.util.Random`. `Random` is a 48-bit linear congruential generator. Two outputs and you can work out the seed, then predict every future code. That would hand back the exact enumerability I chose random codes to avoid. One word, and the whole design collapses.

**Don't check whether the code is taken.**

The obvious approach:

```
generate a code
is it taken?  →  no
save it
```

This is broken and no amount of checking fixes it. Between the check and the save, another thread can take that code. Both threads check, both see "free", both insert. It's a TOCTOU race — Time Of Check To Time Of Use.

Checking *is* the problem. The only thing that decides atomically is the unique index on the database column. So I don't check. I insert, and if the database rejects it, that's my signal to try again.

One detail matters: `saveAndFlush`, not `save`. `save` only stages the insert — the SQL fires later, at commit, by which point I'm outside my try/catch and can't retry. `saveAndFlush` forces it now, so the violation lands where I can catch it.

I capped retries at five. Not infinite. Five collisions in a row can't happen by chance, so if it does, the generator is broken or the keyspace is full. An infinite loop would spin forever with nobody noticing. Bounded retries turn an invisible hang into a loud error.

**Validation is a security control, not a formality.**

`https://evil.com` is fine. I'm a redirector, that's the job. These are not:

- `javascript:alert(document.cookie)` — stored XSS, runs in the victim's browser
- `file:///etc/passwd` — reads the server's filesystem
- `http://169.254.169.254/` — the AWS metadata endpoint, SSRF

I allowlist `http` and `https` and reject everything else. Not a blocklist — a blocklist always misses something.

### How I validated it

44 unit tests across the generator, the validator, and the service. All plain objects, no Spring, ~1 second for the lot.

The collision retry test is the one I care about. I can't trigger a real collision — it's 1 in 350,000 and needs two threads on the same instant. So I mocked the repository to reject the first four inserts and accept the fifth, and asserted the service tried five different codes and returned the last one. Then I mocked it to reject everything and asserted it gave up with a clear error after exactly five tries.

That path would otherwise never be exercised until it happened in production.

I also tested the validator against every attack scheme I could think of, including case variants — `JavaScript:alert(1)` as well as `javascript:`. A validator comparing schemes case-sensitively passes the normal tests and gets bypassed with one capital letter. That's how allowlists actually get defeated.

Then curl end-to-end: create a link, hit it, confirm 302.

---

## Scenario 2: Brownfield

### The requirement

"Make it fast" and "add analytics."

The core worked. I now had to change it without breaking it — which is the whole difference between this and the greenfield work. There were callers to think about.

### What I did before touching anything

The point of a brownfield change is knowing what it touches. So I wrote this down first:

| Area | Impact |
|---|---|
| `ShortLinkService.resolve()` | Behaviour changes — reads may not reach the DB |
| `config/` | New `CacheConfig` |
| `RedirectController` | Publishes an event; response unchanged |
| **API contract** | **None** |
| **Database schema (short_link)** | **None** |
| New `click_event` table | Additive only |
| New thread pool | Bounded, with a drop policy |

The headline: a read-path optimisation and an additive feature. No contract changes. That was the constraint I set myself before writing code.

### The work

**Why caching was needed at all.**

I'd chosen 302 over 301 earlier. 301 means "moved permanently" and browsers cache it forever — which kills analytics after the first click and makes a link impossible to revoke. 302 means the browser asks every time. I keep control and I keep the numbers.

The cost is that every single click hits my server. That was a deliberate trade, and the cache is where I pay it back.

Reads outnumber writes about 100:1, and a code's target never changes once created. That's the ideal caching profile.

**Three traps I avoided.**

I didn't cache the JPA entity. A cached entity is detached from its database session — touch a lazy field and it throws, or Hibernate tries to reattach it. I cache a plain immutable record instead.

I cached the data, not the decision. If I cached "this link is valid," a link that expired mid-TTL would keep working. So I cache the fields and evaluate expiry fresh on every read.

And the big one: `@Cacheable` doesn't work when a method calls itself. Spring caching goes through a proxy. If `resolve()` called a cached method inside the same class, the call never leaves the object, never hits the proxy, and the cache silently never works. No error. Just slow. That's why `ShortLinkLookup` is its own class.

**Analytics, with one rule.**

Recording a click must never slow down or break a redirect. The user is waiting. Everything else fell out of that.

So the redirect doesn't write to the database. It publishes an event and returns. A background listener does the writing on a different thread. One line in the controller — `events.publishEvent(...)` — which returns immediately and doesn't know or care who's listening.

Two things in the thread pool are worth explaining.

I configured the executor explicitly. Spring's default is `SimpleAsyncTaskExecutor`, which makes a brand new thread for every task and never reuses them. Fine at five requests a minute. At a thousand a second it makes a thousand threads a second and the JVM dies. It works perfectly in testing and only fails under load, which is the worst time to find out.

And I used `AbortPolicy`, not `CallerRunsPolicy`. When the queue fills, the instinct is `CallerRuns` — "let the calling thread do the work, don't lose data." But the calling thread *is the redirect thread*. So under heavy load, exactly when I'm already struggling, I'd start doing database inserts on the user's critical path. The system gets slow, so it makes itself slower. `AbortPolicy` drops the task instead. I lose a click. That's the right trade — the entire design exists to protect the redirect, and a policy that sacrifices the redirect to save a click has the requirement backwards.

I hashed the client IP rather than storing it. IPs are personal data. A hash still lets me count unique visitors. I've documented that an unsalted SHA-256 of an IPv4 address is brute-forceable — only ~4 billion candidates — and that production needs a rotating salt. Better to say where the defence is weak than to claim it's solid.

### How I validated it

This is the part I'd point at, because the normal checks were useless.

**The cache.** Both redirects returned an identical 302 in about 20ms. A broken cache looks exactly the same from the outside. A misconfigured `@Cacheable` fails silently — correct results, no error, no cache.

So I turned on SQL logging and hit the same link twice. One SELECT. The second request never touched the database. I confirmed it independently through `/actuator/metrics/cache.gets`: one hit, one miss.

I verified it by proving what the database *didn't* do.

**The async.** Same problem. The response tells you nothing. So I watched the thread names in the log:

```
[nio-8080-exec-3]  select from short_link      ← redirect, cache miss
[   analytics-1]   insert into click_event
[   analytics-2]   insert into click_event
[   analytics-1]   insert into click_event
```

Every click insert on `analytics-N`, never on `nio-8080-exec-N`. That's the proof the write is genuinely off the request thread. And `analytics-1` appearing twice proves the pool is reusing threads rather than making new ones.

**The contract.** `RedirectControllerTest` still passes unchanged. Same status, same headers, same body. I changed the performance characteristics of the hot path and didn't move a single contract.

**End to end.** An integration test with the real context, real H2, real cache, real executor: create a link, hit it three times, wait for the counts to catch up, check the stats endpoint reads 3.

I used Awaitility for that wait, not `Thread.sleep`. `sleep(500)` always takes 500ms and fails randomly when the machine is busy and the insert takes 600. Awaitility polls until it's true — passes in 20ms locally, tolerates 5 seconds on a loaded box. `Thread.sleep` in a test is how you get a suite nobody trusts.

---

## Scenario 3: Ambiguous

### The ambiguity

Nothing in the assignment says where redirects live. It says "build a URL shortener." That's it.

I found the problem while designing the generator, not while reading the brief. My app already serves real paths — `/actuator`, `/h2-console`, `/swagger-ui`, `/favicon.ico`. If short codes live at the root as `/{code}`, then `SecureRandom` will eventually produce the string `favicon`. Seven characters, all in my alphabet. Then that link is broken, or worse, points somewhere it shouldn't.

Nobody told me which mattered more: the shortest possible URL, or the guarantee that this can't happen.

### The options

**Option A — namespace it.** `/r/{code}`. Every short code lives under `/r/`, so it can never collide with anything at the root. Ever. Including root paths someone adds three years from now who has never read my code. Costs two characters.

**Option B — reserved-word blocklist.** Keep `/{code}`. Maintain a list — `actuator`, `h2-console`, `swagger-ui`, `favicon.ico`, `error` — and throw away any generated code that matches. Shortest possible URLs. About five lines.

### What I chose and why

I chose A. The AI recommended B.

The AI's reasoning was reasonable on its face: a URL shortener whose URLs aren't short is a contradiction, and the blocklist is trivial to write. I didn't buy it.

The problem with B isn't that it's wrong today. It's right today. The problem is its failure mode. Someone adds a root-level path six months from now — a health check, a static asset, anything — and doesn't know the blocklist exists. An old short link quietly starts resolving to the wrong place. Nothing fails. Nothing logs. Nobody finds out until a user complains, and by then nobody connects it to a route added half a year ago.

Option A can't fail that way. Not "is unlikely to" — can't. The collision is impossible by construction, and it stays impossible without anyone maintaining anything.

Two characters against a silent, deferred, cross-team failure. That's not close.

### The other ambiguities

The `/r/` decision is the one I acted on, but the brief had more. "Analytics" and "reliability" were both undefined:

- **"Analytics"** could be a click counter or per-click detail with timestamp, referrer, user agent, and geo. Those differ by orders of magnitude in storage. I chose per-click event capture, aggregated on read, on the grounds that you can always aggregate detail you kept and never recover detail you didn't.
- **"Reliability"** had no SLO attached. I assumed read-heavy (~100:1), set myself a p99 under 50ms on the redirect, and made that the reason analytics had to be async.
- **Cache TTL** — 5 minutes. This one is genuinely a business decision I made as a technical one, and I've flagged it. It's the answer to "how long can a revoked link keep working," and at a brokerage that's not mine to decide alone.

### How I validated it

The path is config, not a constant: `@RequestMapping("${app.redirect-path}")`. Defined once in `application.yml`, so changing it is one line and everything follows.

`RedirectControllerTest` pins the behaviour, and the integration test walks `/r/{code}` end to end.

I also left a note in the limitations doc: the `/r/` prefix removes the collision risk, and it's the reason I never needed a blocklist. If anyone moves redirects back to the root later, they inherit the problem I avoided.

---

## What the three had in common

Different work, same shape.

**Greenfield** was about getting the non-obvious things right the first time, because enumerable codes and TOCTOU races aren't things you retrofit.

**Brownfield** was about knowing what I was touching before I touched it, and proving I hadn't broken anything. The contract tests passing unchanged is the whole point.

**Ambiguous** was about the fact that nobody was going to tell me the answer, and picking the option whose failure mode I could live with.

The thread running through all three: **I verified by looking at the layer below the one I changed.** The cache, the async executor, the Flyway migration — all of them looked fine from the HTTP response. Only the SQL log, the thread names, and the startup output told the truth.
