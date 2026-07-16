# URL Shortener — Project Notes

**Author:** Jayanth Reddy Aeradla
**Date:** 15 July 2026
**Assessment:** AI-Proficient Software Engineer

---

## 1. What we are building

A URL shortener.

- You give it a long URL → it gives you back a short one.
- You open the short one → it sends you to the long one.
- It counts the clicks.

Example:
```
https://www.example.com/some/very/long/path
        ↓
http://localhost:8080/r/DNd364w
```

## 2. What the assessment is really testing

This is the important part. The scoring is:

| Category | Weight |
|---|---|
| Problem Understanding & Engineering Reasoning | 15% |
| Software Design & Architecture | 20% |
| **AI-Assisted Development Proficiency** | **20%** |
| Code Quality | 15% |
| Testing | 10% |
| Security & Production Readiness | 10% |
| Operational Excellence | 5% |
| Communication & Ownership | 5% |

Only about 25–30% is "does the code work".
About 70% is **how I thought, what I decided, and how I used AI**.

So the documents matter as much as the code.

---

## 3. The technology stack

| Thing | What it is | Why |
|---|---|---|
| Java 17 | Language | Already installed |
| Spring Boot 4.1.0 | Web framework | What Spring Initializr gave us |
| Maven | Build tool | Standard |
| H2 | In-memory database | No install needed. **Data is wiped on every restart.** |
| Flyway | Creates database tables | Versioned SQL scripts instead of letting Hibernate guess |
| Caffeine | Cache | Makes redirects fast |
| Lombok | Removes boilerplate | Only `@Getter`, used carefully |
| SpringDoc | API documentation | Live Swagger page |
| JaCoCo | Test coverage report | Gives a number to quote |
| Actuator | Health and metrics | Production monitoring |

---

## 4. What we built, step by step

### Step 1 — Project setup
Created a Spring Boot project from start.spring.io. Added dependencies to `pom.xml`.

### Step 2 — Configuration (`application.yml`)
Set up the database, cache, logging, and API docs.

### Step 3 — The data model
- `V1__create_short_link.sql` — creates the `short_link` table
- `ShortLink.java` — the Java class that maps to that table
- `ShortLinkRepository.java` — how we read and write it

### Step 4 — Making the short code
- `ShortCodeGenerator.java` (interface)
- `Base62RandomShortCodeGenerator.java` — makes 7 random characters

### Step 5 — The business logic
- `ShortLinkService.java` — create and look up links
- `UrlValidator.java` — blocks dangerous URLs
- Exception classes for the different failures

### Step 6 — The web layer
- `ShortLinkController.java` — the JSON API (`POST /api/v1/links`)
- `RedirectController.java` — the actual redirect (`GET /r/{code}`)
- DTO records for request and response

### Step 7 — Error handling
- `GlobalExceptionHandler.java` — turns errors into clean JSON, no stack traces

### Step 8 — Caching
- `CachedLink.java`, `ShortLinkLookup.java`, `CacheConfig.java`
- Second visit to the same link does not touch the database

**Current status: all of the above is working and tested by hand.**

---

## 5. The decisions I made and why

These are the interview questions. Each one has a reason.

### 5.1 Random short codes, not counting

**Options:**
1. Count up (1, 2, 3...) and convert to letters
2. Pick 7 random characters
3. Hash the URL

**Chose: random.**

**Why:** counting means the codes are guessable. Anyone can visit `/r/1`, `/r/2`, `/r/3` and read every link in the system. At a brokerage that could mean internal documents. It also leaks how many links we have — make one link, look at the number, now you know our volume.

**Cost:** two random codes could clash. With 7 characters there are 3.5 trillion possibilities, so at 10 million links the chance of a clash is about 1 in 350,000. We handle it with a retry.

### 5.2 `SecureRandom`, not `Random`

`java.util.Random` is predictable. See two codes, work out the pattern, predict all future codes. That would undo the whole point of 5.1.

### 5.3 Short codes live under `/r/`

**Problem:** our app already has real paths — `/actuator`, `/h2-console`, `/swagger-ui`. One day a random code will match one of those.

**Options:**
- A: put redirects under `/r/` — can never clash
- B: keep a banned-words list — shorter URLs, but if someone adds a new path later and forgets the list, links break silently

**Chose: A.** Two extra characters is cheap. A silent failure that appears months later is not.

### 5.4 Do not check before inserting — this is the big one

**The wrong way:**
```
1. Make a code
2. Check if it's taken     ← check
3. Save it                 ← use
```

This is broken. Between step 2 and step 3, another user can save the same code. Both threads check, both see "free", both save. This is called a **TOCTOU race** (Time Of Check To Time Of Use).

**You cannot fix this with a better check. Checking IS the problem.**

**The right way:** just try to save. The database has a unique index on `short_code`. Only the database can decide, and it decides atomically. If it rejects us, make a new code and try again. Give up after 5 tries.

**Also:** we use `saveAndFlush`, not `save`. `save` waits until the end of the transaction to actually run the SQL — by then we're outside our try/catch and cannot retry. `saveAndFlush` runs it immediately.

### 5.5 Retry 5 times, then fail loudly

Not infinite. 5 clashes in a row is basically impossible by chance. If it happens, something is genuinely broken. An infinite loop would spin forever and nobody would know.

### 5.6 302 redirect, not 301

**301 = "moved permanently". Browsers cache it forever.**

That means:
- Analytics die. The browser stops asking us. We count one click and nothing after.
- We can never turn a link off. Every browser that saw it keeps redirecting. Zero control.

**302 = "found".** The browser asks us every time. We keep control and we keep the click counts.

**Cost:** every click hits our server. That is why we added the cache in Step 8. **We traded speed for control, then bought the speed back.**

### 5.7 URL validation is a security control

We only allow `http` and `https`. Everything else is rejected.

Blocked examples:
- `javascript:alert(1)` — would be a stored XSS attack
- `file:///etc/passwd` — reads local files
- `http://169.254.169.254/` — AWS metadata, an SSRF attack

**Allowlist, not blocklist.** A blocklist always misses something.

**Known gap:** we do not block URLs that resolve to internal addresses. Doing it properly needs a DNS check at redirect time, because DNS can change after we validate. Out of scope — documented, not fixed.

### 5.8 Never return the entity from the controller

We use separate DTO records. Returning the database class would leak the internal `id` and `active` flag, tie our API to our table structure, and can trigger accidental database queries during JSON conversion.

### 5.9 No `@Data` on entities

Lombok's `@Data` on a JPA entity breaks in three ways:
1. `equals`/`hashCode` use every field. Save the object, the database sets the `id`, the hashcode changes, and the object vanishes from any `HashSet` it was in.
2. `toString` touches every field — can fire database queries from a log line.
3. It generates `setId()`. Nothing should be able to change an entity's identity.

We use `@Getter` only, plus a constructor that enforces the rules.

### 5.10 Errors: right status code, no stack traces

**Before:** a missing link returned **500**. A bad URL returned **500**. Both with a full stack trace.

**Why that is bad:**
- 500 means "our fault". A user typing a bad code is not our fault. Every monitoring system alerts on 500 — we would page someone at 3am because a user made a typo.
- Stack traces leak our package names, framework versions, and class structure. Free reconnaissance for an attacker.

**After:**

| Situation | Status | Meaning |
|---|---|---|
| Link not found | 404 | Client error |
| Bad URL | 400 | Client error |
| Missing field | 400 | Client error |
| Cannot generate code | 503 | Our fault, try again |
| Anything unexpected | 500 | Our fault, logged in full |

**Log levels are a design decision too:**
- 404 → `debug`. Normal traffic. Logging it as an error means 99% noise and you miss real problems.
- Generation failure → `error`. Something is actually broken.

**The distinction between "client did something wrong" and "we did something wrong" is what makes alerting possible.**

We use RFC 9457 `ProblemDetail` — a standard error format, so clients can parse our errors without reading our docs.

### 5.11 The cache

**Why:** we chose 302, so every click hits us. Reads outnumber writes about 100:1, and a short code's target never changes. Ideal for caching.

**Three traps we avoided:**

1. **Do not cache the entity.** A cached entity is detached from the database session and breaks. We cache a plain immutable record instead.
2. **Cache the data, not the decision.** If we cached "this link is valid", a link that expires would keep working until the cache clears. We cache the fields and check expiry fresh every time.
3. **`@Cacheable` does not work on a method calling itself.** Spring caching works through a proxy. If `resolve()` called a cached method inside the same class, the call never leaves the object, never hits the proxy, and **the cache silently never works**. No error. Just slow. That is why `ShortLinkLookup` is a separate class.

**Settings:** `maximumSize=10000, expireAfterWrite=5m, recordStats`

- `maximumSize` — without it the cache grows forever and eventually crashes the app
- `expireAfterWrite=5m` — **this is the honest limitation.** Turn a link off and it can keep working for up to 5 minutes. With multiple servers, each has its own cache and clearing one does not clear the others. 5 minutes is the worst case. At a brokerage that is a business decision, not a technical one. A shared Redis cache would fix it properly.
- `recordStats` — a cache you cannot measure is a cache you cannot tune

---

## 6. AI usage log

**This is 20% of the grade.** These are real, from today.

### Entry 1 — Wrong dependency names (Spring Boot 3 vs 4)
AI suggested Spring Boot 3.3.x with `spring-boot-starter-web`. Initializr actually produced 4.1.0 with `spring-boot-starter-webmvc` and separate `-test` starters.

**Root cause:** the model's training data predates Spring Boot 4's modularization.
**Action:** rejected the AI's dependency block, verified every coordinate against Maven Central.
**Lesson:** library coordinates are the highest-risk category for AI hallucination, because the model is *confidently* wrong, not uncertain.

### Entry 2 — Verified coordinates that were correct
AI flagged three dependencies as unverified. All three resolved cleanly.

**Why logged:** a log that only records the catches overstates its own hit rate. Verification was applied uniformly.

### Entry 3 — SpringDoc major version
AI suggested springdoc 2.6.0. Boot 4 needs springdoc 3.x, because Boot 4 moved to Jackson 3 and broke the 2.x integration.

**Why it matters:** this would have compiled fine and failed at runtime.

### Entry 4 — Flyway silently did nothing
AI said to add `flyway-core`. In Boot 3 that was enough. In Boot 4 you need `spring-boot-starter-flyway` — the autoconfiguration now lives in a separate module.

**The failure was silent.** No Flyway log lines at all. The autoconfiguration class was gated on a condition, but the class lived in a jar we did not have — **a condition in a jar you do not have does not fail, it just does not exist.**

**The generalization:** three failures, same root cause, escalating in subtlety — wrong name (loud compile error) → wrong version (runtime break) → missing module (silent no-op). An AI's error rate is not uniform. It spikes wherever a framework changed after the training cutoff, **and the model has no signal that it is in that zone.** It is equally confident on both sides of the line.

**Rule adopted:** "it compiles" is not verification.

### Entry 5 — AI over-applied its own recent failure pattern
The actuator log showed 3 endpoints when 4 were configured. The AI guessed "another missing Boot 4 module" — pattern-matching its three previous failures.

**Wrong.** The real answer was in a log already supplied: Spring's test framework deliberately disables metrics export in tests. Nothing was broken.

**Lesson:** an AI that has just been wrong three times the same way will over-apply that explanation to the next anomaly. Recent failures become a prior that fires on unrelated evidence. **Test the hypothesis against the data, do not just re-ask the model.**

### Entry 6 — Rejected the AI's recommendation
On the reserved-word problem, the AI recommended Option B (blocklist) for shorter URLs. I chose Option A (`/r/` prefix) because a silent failure mode that appears months later is worse than two extra characters.

### Entry 7 — Diagnostic error: blamed the wrong system
A redirect returned 404 in Postman. I assumed my app was broken. It was not — Postman follows redirects by default and only shows the final response. The 404 came from example.com.

**Lesson:** when a tool aggregates several steps, confirm which step produced the output you are reading.

### Entry 8 — Verified the cache by absence of evidence
Two redirects produced exactly one SELECT in the SQL log.

**The point:** both responses were identical, 302, ~20ms. A broken cache looks exactly the same from outside. A misconfigured `@Cacheable` fails silently — correct results, no error, no cache. **The only way to verify it was to watch the layer below the one under test.**

### Entry 9 — Tooling lies
`mvn spring-boot:run` prints `BUILD SUCCESS` even when the application failed to start. It reports that the plugin ran, not that the app is healthy. Confirmed startup by the "Started UrlShortenerApplication in Xs" line instead.

### Entry 10 — Bad config is silent, three times
- DevTools quietly turned stack traces on in error responses
- Missing cache config quietly gave an unbounded, no-TTL cache
- A misplaced YAML block was quietly ignored

**Pattern:** none of these failed. Each one silently substituted a default I never chose. **Security holes usually arrive as defaults, not as mistakes.**

### Entry 11 — AI gave a code fragment that could not compile
The AI handed over a field with a comment saying "add to constructor" rather than the complete file. It did not compile.

**Lesson:** AI output must be complete and runnable, or the human is doing the integration work by hand and introducing errors.

---

## 7. Known limitations (be honest about these)

1. **H2 in-memory only.** Every restart wipes all data. Postgres was scoped out.
2. **No authentication.** Anyone can create links. Real deployment needs auth on create.
3. **No rate limiting yet.** Step 10.
4. **Cache staleness up to 5 minutes.** A revoked link can keep working. Multiple servers make this worse.
5. **No SSRF protection.** URLs pointing at internal addresses are not blocked.
6. **IP hashing is unsalted.** SHA-256 of an IPv4 address can be brute-forced (only ~4 billion options). Needs a rotating salt in production.
7. **H2 2.4.240 is newer than Flyway has verified** (2.3.232). Running an untested combination.
8. **OpenAPI 3.1**, not 3.0 — some older tools cannot read it.
9. **`/h2-console` and Swagger are open.** Fine for an assessment, not for production.

### If this went to production
- Postgres instead of H2, config injected from outside
- H2 console removed, Swagger disabled or behind auth
- `/actuator/metrics` not exposed; `/actuator/prometheus` on an internal port only
- Secrets from a vault, not `application.yml`
- Shared Redis cache so eviction works across servers

---

## 8. Still to do

| Step | What | Why it matters |
|---|---|---|
| 9 | Click analytics (async) | Brownfield scenario |
| 10 | Rate limiting | Ambiguous-requirement scenario |
| 11 | Observability | 5% of grade |
| 12 | Tests | 10% of grade |
| 13 | Documents | 5% + feeds the 20% |

**The three scenarios the assignment asks for:**
- **Greenfield** — Steps 3–7. Built from nothing.
- **Brownfield** — Steps 8–9. Took working code and extended it without breaking the API.
- **Ambiguous** — Step 10. A vague requirement, interrogated and interpreted.

---

## 9. How to run it

```bash
# Build and test
mvn clean verify

# Start (holds the terminal)
mvn spring-boot:run

# Stop
Ctrl+C

# If port 8080 is stuck
lsof -ti:8080 | xargs kill
```

**Create a link:**
```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com"}'
```

**Use it** (no `-L`, so curl shows our 302 rather than following it):
```bash
curl -i http://localhost:8080/r/YOUR_CODE
```

**Other URLs:**
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- H2 console: `http://localhost:8080/h2-console`
- Health: `http://localhost:8080/actuator/health`

**Remember:** H2 is in-memory. Restart the app and all your links are gone.
