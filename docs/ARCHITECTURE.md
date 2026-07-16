# Architecture

**Project:** URL Shortener
**Author:** Jayanth Reddy Aeradla
**Date:** 15 July 2026

---

## 1. What this is

A service that takes a long URL and hands back a short one. Open the short one and it sends you to the long one. It counts the clicks.

```
POST /api/v1/links          →  https://example.com/very/long/path
                            ←  http://localhost:8080/r/DNd364w

GET  /r/DNd364w             →  302, Location: https://example.com/very/long/path
```

That's the whole feature. Most of the engineering is in three places that aren't obvious from the description: how the short code is generated, how two users creating a link at the same instant are handled, and how a click gets counted without making the user wait.

---

## 2. The assumptions I'm building on

The requirement I was given is one sentence: "build a URL shortener with core APIs, analytics, and reliability features." That leaves a lot open. Here's what I decided, and these drive everything else.

**Who uses it.** An internal enterprise service. Creating a link would be authenticated; following one is anonymous. That shapes the threat model — the people creating links are employees, the people clicking them could be anyone.

**Traffic shape.** Read-heavy, roughly 100 reads per write. Real shorteners look like this — you create a link once and it gets clicked many times. This single assumption drives the caching design.

**Scale.** Assume 10 million links, peaking around 1,000 redirects a second. Those numbers decide the short code strategy.

**Latency.** Redirect p99 under 50ms. It's in the user's critical path — they're staring at a blank tab. This is why analytics can't be synchronous.

**Analytics depth.** Per-click detail, not a counter. Timestamp, referrer, user agent, hashed IP. Reasoning: you can always aggregate detail you kept, and you can never recover detail you didn't.

None of these were given to me. If any is wrong, parts of the design are wrong with it, and I'd want to know before building further.

---

## 3. The pieces

Four layers, plus two things off to the side.

```
   HTTP
     │
     ▼
┌─────────────────────┐
│  Controllers        │   HTTP in, HTTP out. Nothing else.
│  ShortLinkController│
│  RedirectController │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐          ┌──────────────────┐
│  Service            │─────────▶│  ShortLinkLookup │──▶ Caffeine cache
│  ShortLinkService   │          └──────────────────┘
│  UrlValidator       │
│  ShortCodeGenerator │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Repositories       │   Doorway to storage. Interfaces only.
│  ShortLinkRepository│
│  ClickEventRepo     │
└──────────┬──────────┘
           │
           ▼
        ┌──────┐
        │  H2  │   (Postgres in a real deployment)
        └──────┘

     off to the side:
     RedirectController ──publishes event──▶ ClickEventListener ──▶ click_event
                                            (background thread pool)
```

**Controllers** speak HTTP. They read JSON, call the service, turn the answer into a response. They don't know a database exists.

**Service** holds the rules. Validation, code generation, collision handling, expiry. It doesn't know HTTP exists.

**Repositories** are the doorway to storage. They're interfaces — I wrote no code in them, just method names. Spring generates the SQL.

**H2** actually stores the rows.

### Why they're separate

The usual answer is "it's cleaner." That's not the real reason and it doesn't hold up to a follow-up question.

The real reason is that each layer can be tested on its own, and each can change without the others.

`ShortLinkServiceTest` runs 13 tests in 0.9 seconds. No Spring, no HTTP, no database — just `new ShortLinkService(mockRepo, mockGenerator, ...)`. The collision retry test works by telling a fake repository to reject four inserts and accept the fifth. **That test is only possible because the logic lives somewhere that doesn't know HTTP exists.**

Put that same logic in the controller and it still works — the app behaves identically. But now to test a code collision, which has nothing to do with HTTP, I have to boot Spring, fake a servlet request, build JSON, parse JSON back out, and read a status code. Three seconds instead of 0.9. And realistically that test never gets written, because slow awkward tests don't.

It runs the other way too. `ShortLinkControllerTest` mocks the service entirely and tests only the HTTP contract — status codes, headers, JSON shape. No database, no rules.

The 98% coverage on the service package is downstream of this. The logic was reachable without machinery.

Same argument for the repository: swap H2 for Postgres and nothing in the service changes, because the service only ever talks to an interface.

---

## 4. What happens on each request

### Creating a link

```
POST /api/v1/links  {"url": "https://example.com"}
   │
   ├─ @Valid on the DTO         → 400 if url is blank or over 2048 chars
   │
   ├─ UrlValidator.validate()   → 400 if the scheme isn't http/https
   │
   ├─ generator.generate()      → 7 random Base62 chars
   │
   ├─ repository.saveAndFlush() → DB rejects duplicates via unique index
   │      └─ on rejection: new code, try again, up to 5 times
   │
   └─ 201 Created + Location header
```

Validation happens twice on purpose. The DTO annotation guards the HTTP boundary; `UrlValidator` guards the domain regardless of who's calling. Not duplication — different jobs.

### Following a link

```
GET /r/DNd364w
   │
   ├─ service.resolveTarget()
   │      └─ ShortLinkLookup.find()  → cache hit? return. miss? one SELECT, then cache it.
   │      └─ check expiry against the injected clock
   │
   ├─ events.publishEvent(...)   → returns immediately, doesn't wait
   │
   └─ 302 + Location + Cache-Control: no-store
```

The publish is fire-and-forget. The controller doesn't know who's listening and doesn't wait to find out.

### Reading stats

```
GET /api/v1/links/DNd364w/stats
   │
   ├─ service.resolve()      → 404 if the code doesn't exist
   │                            (deliberately not zeroes — see §6.5)
   ├─ three COUNT queries
   │
   └─ 200 + counts
```

---

## 5. The data model

Two tables. No foreign key between them.

```sql
short_link
├─ id              BIGINT       identity
├─ short_code      VARCHAR(16)  NOT NULL, UNIQUE INDEX
├─ long_url        VARCHAR(2048) NOT NULL
├─ created_at      TIMESTAMP    NOT NULL
├─ expires_at      TIMESTAMP    nullable
└─ active          BOOLEAN      NOT NULL

click_event
├─ id              BIGINT       identity
├─ short_code      VARCHAR(16)  NOT NULL
├─ clicked_at      TIMESTAMP    NOT NULL
├─ referrer        VARCHAR(512)
├─ user_agent      VARCHAR(512)
└─ client_ip_hash  VARCHAR(64)

INDEX ix_click_event_code_time ON click_event (short_code, clicked_at)
```

**The unique index on `short_code` is load-bearing.** It's not a nice-to-have constraint — it's the only thing that decides collisions atomically. §6.2 explains why.

**`long_url` is capped at 2048.** There's no length limit in the HTTP spec. 2048 is the historical browser ceiling and a deliberate, documented cap. It's enforced in three places: the column, the DTO annotation, and the validator.

**No foreign key from `click_event` to `short_link`.** Deliberate. Every click is an insert, and a foreign key would make the database look up and lock the parent row every single time — a cost on the hot path for little benefit. It also lets click history outlive a deleted link. Normally I'd want the FK; here I don't.

**The click index is `(short_code, clicked_at)` in that order.** Every analytics query is "clicks for this code," often "in the last 24 hours." Code first narrows the set; time second sorts within it. Reversed, the index would be useless for the common query.

**`active` is a flag, not a delete.** Hard-deleting a link orphans its analytics and destroys audit history. Soft delete.

**Schema is owned by Flyway, verified by Hibernate.** `ddl-auto: validate`, not `update`. Flyway's versioned SQL creates the tables; Hibernate compares the entities to what's actually there and refuses to start if they've drifted. `update` is what people reach for and it's wrong in production — it can't drop, can't rename, can't reorder, and silently diverges between environments.

---

## 6. The decisions

These are the six that took actual thought.

### 6.1 Random short codes, not a counter

**The obvious approach:** auto-increment the ID and Base62-encode it. Link 1 gets `1`, link 125 gets `21`. Zero collisions by construction, shortest possible codes, no retry logic.

**Why I didn't:** the codes are enumerable. Anyone can walk `/r/1`, `/r/2`, `/r/3` and harvest every link in the system. At a brokerage that could mean internal reports or client document links. It also leaks business volume — create one link, read the number, and you know exactly how many links exist.

**What I did:** 7 random Base62 characters. 62⁷ ≈ 3.5 trillion possibilities. At 10 million links the keyspace is 0.0003% full, so the chance any *new* code hits an existing one is about 1 in 350,000.

Worth being precise about that number: it's not the birthday problem. I'm not asking "do any two of 10 million codes collide" — that probability is high. I'm asking "does this one new code hit an existing one," because the database checks each insert individually.

**`SecureRandom`, not `Random`.** `java.util.Random` is a 48-bit linear congruential generator. Two consecutive outputs and an attacker can recover the seed and predict every future code — which hands back the exact enumerability I chose random codes to avoid. One word, and the whole design collapses.

**`nextInt(bound)`, not `nextInt() % 62`.** Modulo on a power-of-two range doesn't divide evenly by 62, so early characters appear slightly more often. Biased output means a smaller effective keyspace. `nextInt(bound)` uses rejection sampling and is unbiased.

**The cost:** collisions are now possible. §6.2 is how they're handled.

### 6.2 Don't check whether the code is taken

This is the one I'd most want to be asked about.

**The wrong way:**

```java
String code = generator.generate();
if (repository.existsByShortCode(code)) {   // check
    code = generator.generate();
}
repository.save(new ShortLink(code, url));  // use
```

Broken. Between the check and the save, another thread can insert the same code. Both threads check, both see "free", both insert. It's a TOCTOU race — Time Of Check To Time Of Use.

**No better check fixes this. Checking is the problem.** Any check happens at some point in time, and the world can change afterwards.

The only thing that decides atomically is the unique index. It's a single point of arbitration inside the database, and it either accepts the row or it doesn't.

**So I don't check. I insert, and treat rejection as the signal to retry:**

```java
for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    String code = generator.generate();
    try {
        return repository.saveAndFlush(new ShortLink(code, longUrl, now, expiresAt));
    } catch (DataIntegrityViolationException e) {
        log.warn("Short code collision on '{}', attempt {}/{}", code, attempt, MAX_ATTEMPTS);
    }
}
throw new ShortCodeGenerationException("...after 5 attempts");
```

**`saveAndFlush`, not `save`.** `save` only stages the insert — the SQL fires later, when the transaction commits, by which point I'm outside the try/catch and can't retry. `saveAndFlush` forces the INSERT now, so the violation lands where I can catch it. Easy to get wrong and it wouldn't fail until it mattered.

**Capped at 5, not infinite.** Five collisions in a row is roughly (1/350,000)⁵ — it cannot happen by chance. If it does, the generator is broken or the keyspace is exhausted. An infinite loop would spin forever and nobody would find out. A bounded retry turns an invisible hang into a loud 503.

### 6.3 302, not 301

**301 means "moved permanently" and browsers cache it forever.** Not for an hour — permanently, until someone clears their cache. That means:

- **Analytics die.** The browser stops asking me. I count the first click and nothing after.
- **I can never revoke a link.** Deactivate it, delete it, doesn't matter — every browser that's seen it keeps redirecting. Zero control. For a brokerage, a link to a mistaken document can't be taken down.

**302 means the browser asks every time.** I keep control and I keep the numbers.

**The cost is real:** every single click hits my server, forever. That's a deliberate trade — I paid latency to buy revocability and measurement, and then bought the latency back with the cache in §6.4.

That's the honest framing. Not "302 is correct" — "I traded performance for control, and here's how I recovered the performance."

`Cache-Control: no-store` on the response as well, so intermediaries don't cache the hop either.

### 6.4 The cache

Reads outnumber writes ~100:1, and a code's target never changes once created. That's the ideal caching profile, and §6.3 is why it's needed.

**Three traps, all avoided deliberately:**

**Don't cache the JPA entity.** A cached entity is detached from its persistence context. Touch a lazy field and it throws; Hibernate may try to reattach it. I cache `CachedLink`, a plain immutable record.

**Cache the data, not the decision.** If I cached "this link is valid," a link that expired mid-TTL would keep redirecting. I cache the fields — including `expiresAt` and `active` — and evaluate resolvability fresh on every read against the injected clock.

**`@Cacheable` doesn't work on a self-invocation.** This is the one that would have silently sunk it. Spring caching works through a proxy. If `resolve()` called a cached method inside the same class, the call never leaves the object, never passes the proxy, and **the cache is silently never used.** No error. Correct results. Just slow. That's the entire reason `ShortLinkLookup` is a separate bean.

**Settings:** `maximumSize=10000, expireAfterWrite=5m, recordStats`

- `maximumSize` — without it the cache grows forever. Spring's default cache manager, if you don't configure one, is an unbounded `ConcurrentHashMap` with no eviction. That's a memory leak waiting for enough traffic.
- `expireAfterWrite=5m` — the honest one. Deactivate a link and it can keep working for up to 5 minutes. With multiple nodes it's worse: each has its own Caffeine cache, and evicting on one does nothing to the others. **The TTL is what bounds the staleness window across a cluster.** Five minutes is really the answer to "how long can a revoked link keep working," and at a brokerage that's a business decision I made as a technical one. A shared Redis cache fixes it properly.
- `recordStats` — a cache you can't measure is a cache you can't tune.

### 6.5 Errors: the client's fault vs mine

Before the global handler, a missing link returned **500** and a bad URL returned **500**, both with a full stack trace.

Three things wrong with that:

**Wrong status.** 500 means "our fault." A user typing a bad code is not our fault. Every monitoring system on earth alerts on 5xx — that setup pages an engineer at 3am because someone made a typo.

**Stack traces are reconnaissance.** Package names, framework versions, class structure, handed out for free.

**No consistent shape.** Clients can't parse errors reliably.

**Now:**

| Situation | Status | Whose fault |
|---|---|---|
| Link not found / expired | 404 | Client |
| Bad URL scheme | 400 | Client |
| Missing field, malformed JSON | 400 | Client |
| Can't generate a unique code | 503 | Mine — transient, retry |
| Anything unexpected | 500 | Mine — logged in full |

**RFC 9457 `ProblemDetail`**, not a custom error class. It's a standard — `type`, `title`, `status`, `detail`, `instance`. Clients parse it without reading my docs. Rolling my own means inventing a format nobody speaks.

**Log levels are a design decision, not decoration.** 404 logs at `debug` — a user typing a bad code is normal traffic, and logging it as an error means the dashboard is 99% noise and the real incident gets missed. Code generation failure logs at `error`, because that one means something is genuinely broken. **The distinction between "the client did something wrong" and "we did something wrong" is what makes alerting possible at all.**

**404s don't echo the code back.** The exception carries `"No active link for code: secret1"` internally; the response says `"No active link exists for that code."` Echoing it is a small aid to anyone enumerating.

### 6.6 URL validation is a security control

`https://evil.com` is fine — I'm a redirector, that's the job. These are not:

| Input | Attack |
|---|---|
| `javascript:alert(document.cookie)` | Stored XSS, runs in the victim's browser |
| `data:text/html,<script>...` | Same, inline payload |
| `file:///etc/passwd` | Local file access |
| `http://169.254.169.254/` | AWS metadata endpoint, SSRF |

**Allowlist, never blocklist.** Only `http` and `https`. A blocklist always misses something — there's always another scheme.

Scheme comparison is case-insensitive, because `JavaScript:alert(1)` would otherwise walk straight through.

**Known gap:** I don't block URLs resolving to internal addresses. Doing it properly needs DNS resolution at redirect time, not creation time, because a hostname can be repointed after it's validated. Out of scope, documented, and pinned by a test that asserts the gap exists — so if anyone adds protection later, the test goes red and forces them to notice.

---

## 7. The analytics path

One rule drove the whole design: **recording a click must never slow down or break a redirect.** The user is waiting.

```
RedirectController
     │
     │  events.publishEvent(new ClickRecordedEvent(...))   ← returns instantly
     │
     └──▶ 302 to the user
              ┆
              ┆  (on a different thread, user already gone)
              ▼
       ClickEventListener  @Async("analyticsExecutor")
              │
              └──▶ insert into click_event
```

The controller publishes and returns. It doesn't know who's listening, doesn't wait, doesn't care if the listener fails. Add a second listener tomorrow — push to Kafka, update a dashboard — and the controller doesn't change.

**The event carries plain values, not an entity.** The listener runs on a different thread with no persistence context; a detached entity would be unusable there.

**The listener is its own class** for exactly the same proxy reason as `@Cacheable`. An in-class call would run synchronously on the redirect thread, with no error to tell me.

**It swallows its own exceptions.** By the time it runs, the user has their 302 and is gone. There's nobody to tell. Log it and move on.

### The thread pool — two traps

**The default executor will kill you.** Write `@Async` with no configured executor and Spring uses `SimpleAsyncTaskExecutor`, which creates a brand new thread per task and never pools them. Fine at five requests. At 1,000/sec it makes 1,000 threads/sec and the JVM falls over. It works perfectly in testing and only fails under load — the worst time to find out.

So: `corePoolSize=2, maxPoolSize=4, queueCapacity=500`, threads named `analytics-`.

**`AbortPolicy`, not `CallerRunsPolicy`.** When the queue fills, the instinct is `CallerRunsPolicy` — "let the calling thread do the work, don't lose data."

**But the calling thread is the redirect thread.** So under heavy load, exactly when I'm already struggling, I'd start doing database inserts on the user's critical path. The system gets slow, so it makes itself slower. That's a feedback loop, and it's the precise outcome this design exists to prevent.

`AbortPolicy` throws the task away. **I lose a click. That's the correct trade** — a policy that sacrifices the redirect to save a click has the requirement backwards.

**`AsyncUncaughtExceptionHandler` is configured**, because an `@Async void` method that throws sends the exception nowhere. No caller is waiting. Without it, analytics could be failing 100% and every dashboard would look fine.

### Privacy

I hash the client IP rather than storing it. IPs are personal data, and this is a brokerage. The hash still lets me count unique visitors — same visitor, same hash, counted once — without ever storing who anyone is.

**And I've documented that it's weaker than it looks.** An unsalted SHA-256 of an IPv4 address is brute-forceable: there are only ~4 billion candidates, so anyone with the hashes can compute all of them and match in minutes. Production needs a rotating salt held outside the database. Better to name where the defence is weak than to claim it's solid.

### The consequence

Stats are eventually consistent. Redirect and immediately query `/stats` and you may see a count one short — the insert is still in flight. **That's the design, not a bug.** It's the price of never blocking the redirect, and I'd pay it again. But it looks like a bug to anyone who doesn't know the trade, which is why it's in the limitations doc.

---

## 8. Two read paths, on purpose

```java
resolve(shortCode)        // → ShortLink   — uncached, full entity
resolveTarget(shortCode)  // → String      — cached, just the URL
```

Both find a link by code. They exist separately because they have different callers with different needs.

**`resolveTarget` serves `/r/{code}` — the hot path.** A link is created once and clicked many times. This is the busy road. It's cached, and it returns a single `String`, because the redirect needs exactly one thing: the URL for the `Location` header. It doesn't need the id, the created date, or the active flag.

**`resolve` serves `/api/v1/links/{code}` — the metadata API.** Barely anyone calls it. It's uncached, because a cache nobody hits is memory spent for nothing. It returns the whole entity, because the caller genuinely wants every field.

**`resolveTarget` has no `@Transactional`.** On a cache hit there's no database access at all, so opening a transaction would borrow a connection from the pool and immediately hand it back — pure waste on the hottest path. The transaction lives inside `ShortLinkLookup.find()`, where it's only needed on a miss.

That's the general shape: **the hot path carries as little as possible, the rare path can afford to be complete.**

---

## 9. Other things worth explaining

**`Clock` is injected, not `Instant.now()`.** Scattering `Instant.now()` through the code makes expiry untestable — you'd be writing `Thread.sleep()` in tests. With an injected clock, tests use `Clock.fixed(...)` and time becomes a parameter.

**No `@Data` on entities.** Lombok's `@Data` on a JPA entity fails three ways: `equals`/`hashCode` over all fields means persisting an entity changes its hashcode and it vanishes from any `HashSet` holding it; `toString` over all fields can fire database queries from a log line; and it generates `setId()`, which lets anyone mutate an entity's identity. `@Getter` only, plus a constructor that enforces the invariants, plus named business methods (`deactivate()`) for mutation. `equals`/`hashCode` use `shortCode` — the immutable, unique, business-meaningful natural key — so identity is stable from construction through persistence.

**DTOs, never the entity, from the controller.** Returning the entity leaks the internal `id` and the `active` flag, couples the API to the table structure, and Jackson serializing a JPA entity can trigger lazy loads. `ShortLinkControllerTest` asserts `$.id` doesn't exist — so if someone swaps the DTO for the entity to save code, the test goes red.

**`open-in-view: false`.** Spring Boot defaults this to `true`, which holds a database connection open for the entire request lifecycle so lazy loads work in the view layer. On a redirect service at 1,000 req/s that's connection-pool exhaustion. Off means lazy-loading mistakes fail loudly in the service layer instead of hiding.

**`/api/v1/` from day one.** Adding a version later means breaking every client.

**The redirect path is config, not a constant.** `@RequestMapping("${app.redirect-path}")` — defined once in `application.yml`. Change it in one place and everything follows.

**Flat layered packages, not package-by-feature.** Defensible at this size. Past about three bounded contexts I'd move to package-by-feature — the layering starts costing more than it gives once there are enough features that "all the controllers" isn't a useful grouping.

---

## 10. How this scales

**The app is stateless** except for the cache. Add nodes behind a load balancer and it works — no session state, no sticky routing needed.

**The cache is the problem.** Each node has its own Caffeine instance. Evicting on one does nothing to the others. The 5-minute TTL is what bounds the divergence, and that's a band-aid, not a fix. Redis is the answer: shared cache, eviction actually works, staleness window gone.

**The database is the ceiling.** Redirects are single-row index seeks on a unique index, which is about as cheap as a query gets — and the cache absorbs most of them anyway. Writes are more interesting: `click_event` grows without bound at one row per click. At 1,000 clicks/sec that's 86 million rows a day. It needs partitioning by time, a retention policy, and probably a rollup job that aggregates old detail into daily counts. None of that is built.

**Short code length is a knob.** 7 characters gives 3.5 trillion codes. If it ever got tight, the length is configurable and 8 characters multiplies the space by 62. The generator validates the length at construction, so bad config fails at startup rather than at 3am.

**The read path is the one that's ready.** Cache, stateless nodes, index seek. The write path for analytics is the part I'd expect to break first at scale.

---

## 11. Where the design is honest about itself

Everything below is known. I'd rather name it than have someone find it.

**H2 in-memory only.** Restart and the data's gone. The design isn't H2-coupled — the SQL is standard, the repository is an interface — but I haven't proven that against Postgres, so it's a claim, not a fact.

**No authentication.** Anyone can create links. The biggest real gap for a brokerage. My assumption in §2 was authenticated creation; I didn't build it.

**No rate limiting.** Nothing stops someone hammering create or walking codes.

**No SSRF protection.** See §6.6.

**The IP hash is unsalted.** See §7.

**The exception handler still shadows Spring's resolver.** I fixed malformed JSON, but `@ExceptionHandler(Exception.class)` still runs ahead of Spring's own handling for 405, 415, and missing-parameter errors — all of which currently return 500. The right fix is extending `ResponseEntityExceptionHandler`. Not done.

**Nothing is load tested.** Every performance claim in this document — the cache being worth it, the async keeping the redirect fast, the pool sizes — is reasoning, not measurement. The 100:1 ratio and the 50ms p99 are assumptions I made, not numbers I observed. `maximumSize=10000` and a 500-deep queue are starting points, not tuned values.

**Multi-node behaviour is theory.** Everything ran on one JVM.

---

## 12. What I'd do next

In order:

1. **Auth on create.** Biggest gap, and my own assumptions in §2 depend on it.
2. **Extend `ResponseEntityExceptionHandler`.** Cheap, fixes a whole class of wrong status codes that would otherwise page people.
3. **Redis instead of Caffeine.** Kills the 5-minute staleness window and makes eviction work across nodes.
4. **Postgres profile.** Proves the design isn't H2-coupled instead of claiming it.
5. **Rate limiting.** Filter with token buckets keyed by client IP, 429 with `Retry-After`.
6. **Load test.** Turns everything in §10 from reasoning into evidence, and gives real numbers for cache sizing and pool tuning.
7. **Partition `click_event` and add retention.** The write path is the first thing that breaks at scale.
8. **Salt the IP hash.**

---

## 13. The thread through all of it

Three things in this system are invisible from the HTTP response: whether the cache works, whether the analytics write is really off the request thread, and whether the Flyway migration ran.

All three would have looked completely fine while being completely broken. Two identical 302s in 20ms tell you nothing about a cache. A misconfigured `@Cacheable` returns correct results with no error. A missing autoconfiguration module produces silence, not a failure.

The only way I knew any of them worked was by watching the layer below — the SQL log, the thread names, the startup output. That turned out to be the most useful habit of the whole build, and it's the one I'd carry to the next thing.
