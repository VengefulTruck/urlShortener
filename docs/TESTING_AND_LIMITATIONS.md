# Testing and Limitations

**Project:** URL Shortener
**Author:** Jayanth Reddy Aeradla
**Date:** 15 July 2026

---

# Part 1: Testing

## How I approached it

73 tests. The split is deliberate and uneven.

| Level | Tests | Speed | What it covers |
|---|---|---|---|
| Plain unit | 44 | ~1s | Logic, no Spring at all |
| Web slice | 20 | ~2s | HTTP contract, mocked service |
| Integration | 8 | ~4s | Everything real |
| Context | 1 | ~3s | The app boots |

Most of the value is in the 44 that don't touch Spring. `Base62RandomShortCodeGeneratorTest` runs 6 tests in 0.12 seconds because it's `new Base62RandomShortCodeGenerator(7)` and nothing else. `ShortLinkServiceTest` runs 13 in 0.9 seconds with mocked collaborators.

That speed isn't a nice-to-have. Fast tests get run; slow ones get skipped, and a suite nobody runs is a suite that doesn't work. It's only possible because the logic lives in classes that don't know HTTP or JPA exist — which is the actual argument for the layering, not tidiness.

## The tests that matter

Most of the 73 are routine. These four aren't.

### Collision retry

The one I'd point at first.

Two users can generate the same short code. The odds are about 1 in 350,000 at 10 million links, and you'd need two threads landing on the same instant. I can't make that happen on demand.

So I mocked the repository to reject the first four inserts with `DataIntegrityViolationException` and accept the fifth, then asserted the service tried five *different* codes and returned the last one. Then I mocked it to reject everything, and asserted it gave up after exactly five attempts with a clear error rather than looping forever.

Without mocks, that path never gets exercised until it happens in production at 3am. The log from the test run shows it working:

```
Short code collision on 'c1', attempt 1/5
Short code collision on 'c2', attempt 2/5
Short code collision on 'c3', attempt 3/5
Short code collision on 'c4', attempt 4/5
→ returns 'ok55555'
```

### The security control

`UrlValidatorTest` is the only test proving an actual attack is blocked. It covers `javascript:`, `data:`, `file:`, `ftp:`, `gopher:` — and case variants like `JavaScript:alert(1)`, because a validator comparing schemes case-sensitively passes the obvious tests and gets bypassed with one capital letter.

Boundaries are tested at exactly 2048 and exactly 2049 characters, not "short" and "long". Off-by-one at a limit is the classic bug and it only shows up if you test at the edge.

At the service level I assert `verifyNoInteractions(repository, generator)` when a dangerous URL comes in — proving nothing reaches storage, not just that it threw. The order matters for a security control: validate first, then persist. If someone reorders it, that test goes red.

### Expiry, with a fake clock

`Clock` is injected everywhere rather than calling `Instant.now()`. That's the whole reason expiry is testable:

```java
Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC)
```

The test decides what "now" is. The alternative is `Thread.sleep()` and a test that fails randomly on a busy machine.

### The 302

`doesNotReturn301` looks redundant next to `returns302`. It is, as an assertion.

I kept it because 302 is the least intuitive decision in the project. 301 *sounds* more correct — the link really did move permanently. Someone will eventually "fix" it. This test and its name are what stops them:

```
does NOT return 301 permanent redirect
```

When that fails, the report tells whoever broke it why it matters. A test name is documentation that runs.

## Verifying the invisible

Two things in this app can't be tested through the response, and this was the most useful lesson of the build.

**The cache.** Two redirects to the same code return byte-identical 302s in about 20ms. A broken cache looks exactly the same. A misconfigured `@Cacheable` fails silently — right answer, no error, no cache.

So I turned on SQL logging and counted. Two redirects, one SELECT. Confirmed independently through `/actuator/metrics/cache.gets`: one hit, one miss.

**The async.** Same shape. So I read the thread names:

```
[nio-8080-exec-3]  select from short_link
[   analytics-1]   insert into click_event
[   analytics-2]   insert into click_event
[   analytics-1]   insert into click_event
```

Every click insert on `analytics-N`. `analytics-1` appearing twice also proves the pool reuses threads rather than creating them per task.

Both cases came down to the same thing: **the correctness property lived below the interface, so I had to instrument a different layer to see it.**

## Not using Thread.sleep

The integration test waits for async writes with Awaitility:

```java
await().atMost(Duration.ofSeconds(5))
       .untilAsserted(() -> assertThat(clickEvents.countByShortCode(code)).isEqualTo(3));
```

`Thread.sleep(500)` would also "work". It would also always take 500ms, and fail randomly when a loaded CI box takes 600. Awaitility polls and continues the moment it's true — 20ms locally, tolerant of 5 seconds under load.

Flaky tests are worse than no tests. People start ignoring red builds, and then a real failure hides in the noise.

## Test isolation

The integration test clears both tables and the cache in `@BeforeEach`. H2 keeps the schema across tests in the same context, so without that, `dangerousSchemeNeverPersisted` asserts `count() == 0` and passes or fails depending on which tests ran before it.

Order-dependent tests fail for reasons unrelated to the change that triggered them, which makes them actively misleading.

## Coverage

87% instruction, 67% branch. The overall number is the least interesting part.

| Package | Coverage | Comment |
|---|---|---|
| `service` | **98%** | The hard logic. This is the number that matters. |
| `api.dto` | 100% | Records. Free. |
| `api` | 91% | Controllers |
| `config` | 81% | Wiring |
| `analytics` | 80% | Async path |
| `domain` | **55%** | Getters and constructors |
| root | 37% | `main()`. Nobody tests `main()`. |

**The 55% on `domain` is fine and I'd defend it.** It's Lombok getters. A test asserting `getShortCode()` returns the short code proves nothing and inflates the number without adding confidence. Coverage that measures accessors is measuring the wrong thing.

**The 67% branch coverage is the real gap.** 19 of 58 branches are unhit — mostly defensive null paths. That's a genuine weakness, not a rounding error, and it's the number I'd attack first with more time.

A note on the tooling: JaCoCo warned about stale execution data mid-build. Coverage reports built on stale `.exec` files are silently wrong. Only trust a number from a clean build.

---

# Part 2: Limitations

Everything below is known and deliberate. I'd rather name the gaps than have someone find them.

## Scoped out on purpose

**H2 in-memory only.** Every restart wipes the data. Zero setup steps was worth more than persistence for a 2–3 day assignment. The design isn't H2-coupled — Flyway's SQL is standard, the repository is an interface — but I haven't proven that against Postgres, so it's a claim, not a fact.

**No authentication.** Anyone can create links. A real deployment needs auth on create and anonymous redirect. This is the biggest gap for a brokerage.

**No rate limiting.** Nothing stops someone hammering create or walking codes. The design I'd use: a servlet filter with token buckets in a Caffeine cache keyed by client IP, 429 with `Retry-After` on rejection. Roughly an hour of work. Traded for test coverage, which the rubric weights higher.

## Real gaps in what's built

**Cache staleness, up to 5 minutes.** Deactivate a link and it can keep redirecting for the TTL. The `evict()` hook handles a single node — but with multiple servers, each has its own Caffeine cache and evicting on one does nothing to the others. The TTL is what bounds it.

Five minutes is a number I picked. It's really the answer to "how long can a revoked link keep working," which at a brokerage is a compliance decision, not a technical one. A shared Redis cache fixes it properly and I'd raise it before shipping.

**No SSRF protection.** URLs pointing at internal addresses — `169.254.169.254`, `10.x`, `localhost` — are accepted. Blocking them properly means resolving DNS at redirect time, not creation time, because a hostname can be repointed after it's validated. I've pinned this in a test that asserts the gap exists, so if someone adds protection later the test goes red and forces them to notice.

**Unsalted IP hash.** SHA-256 of an IPv4 address is reversible by brute force — there are only about 4 billion candidates. Anyone with the hashes can compute all of them and match in minutes. It's not the privacy protection it looks like. Production needs a rotating salt held outside the database.

**The catch-all still shadows Spring's resolver.** I fixed malformed JSON, but `@ExceptionHandler(Exception.class)` still runs before Spring's own handling for other framework exceptions:

| Client does | Should get | Actually gets |
|---|---|---|
| `PUT` on a POST-only route | 405 | **500** |
| Sends XML | 415 | **500** |
| Missing required param | 400 | **500** |

Each of those pages an on-call engineer for something a user did. The proper fix is extending `ResponseEntityExceptionHandler`, which maps all of them. Not done.

**Everything is open.** `/h2-console` is an unauthenticated SQL console. Swagger publishes the full API surface. Fine for an assessment, not for anything else.

## Behaviour that looks like a bug but isn't

**Stats lag.** Redirect and immediately check `/stats` and you may see a count one short. The insert is still in flight. That's the cost of never blocking the redirect on an analytics write, and I'd make the same trade again — but it looks like a bug to anyone who doesn't know.

**Analytics are lossy under load.** The executor uses `AbortPolicy`. When the queue fills, clicks get dropped. That's on purpose: `CallerRunsPolicy` would push the database insert back onto the redirect thread exactly when the system is most overloaded, making it slower still. Losing a click beats slowing a redirect. But it means the numbers aren't exact under pressure, and nothing currently counts the drops — which it should.

## Environment

**H2 2.4.240 is newer than Flyway has verified** (2.3.232). Flyway warns about it on every boot. Nothing has broken, but it's an untested combination.

**OpenAPI 3.1, not 3.0.** SpringDoc 3.x emits 3.1. Some older codegen tools can't read it.

## Untested

**No load testing.** Every performance claim in these docs — the cache being worth it, the async keeping the redirect fast, the pool sizes — is reasoning, not measurement. The read:write ratio of 100:1 and the p99 target of 50ms are assumptions I made, not numbers I observed. `maximumSize=10000` and a queue of 500 are starting points, not tuned values.

**Multi-node behaviour is unexercised.** Everything ran on one JVM. The cache coherency problem above is theory.

## What I'd do next, in order

1. **Auth on create.** Biggest real gap.
2. **Extend `ResponseEntityExceptionHandler`.** Cheap, fixes a whole class of wrong status codes.
3. **Rate limiting.** Security control, absence is noticeable.
4. **Postgres profile.** Proves the design isn't H2-coupled instead of just claiming it.
5. **Load test.** Turns the performance reasoning into evidence, and gives real numbers for the cache and pool sizing.
6. **Redis instead of Caffeine.** Makes eviction work across nodes and removes the 5-minute staleness window.
7. **Salt the IP hash.**
