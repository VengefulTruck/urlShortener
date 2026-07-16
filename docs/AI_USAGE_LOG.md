# AI Usage Log

**Project:** URL Shortener
**Author:** Jayanth Reddy Aeradla
**Date:** 15 July 2026
**AI tool used:** Claude (Anthropic), via chat interface

---

## How I used AI

I used AI as a pair programmer, not a code generator. The pattern for every step was:

1. I decided what problem to solve next and what constraints applied.
2. I asked the AI to propose an approach, with the reasoning.
3. I evaluated the reasoning, not the code — if the reason was wrong, the code was irrelevant.
4. I ran it and observed the actual behaviour, not just the return value.
5. I recorded what happened, including the times the AI was right.

The rule I settled on early, after three failures in a row: **"it compiles" is not verification.**

Every entry below is a real event from this build, in order.

---

## Entries

### 1 — Wrong dependency names (Spring Boot 3 vs Spring Boot 4)

**What happened:** AI proposed Spring Boot 3.3.x with `spring-boot-starter-web`. Spring Initializr had actually produced 4.1.0 with `spring-boot-starter-webmvc` and separate per-module `-test` starters.

**Root cause:** the model's training data predates Spring Boot 4's modularization. Boot 4 renamed `spring-boot-starter-web` to `spring-boot-starter-webmvc` and split the monolithic autoconfigure jar into focused modules.

**Action:** rejected the AI's dependency block entirely. Verified against the Spring Boot 4 migration guide.

**Lesson:** library coordinates are the single highest-risk category for AI hallucination — not because the model guesses, but because it is *confidently* wrong. It has no uncertainty signal for "this convention changed after my cutoff."

---

### 2 — Verified coordinates that turned out correct

**What happened:** AI flagged three dependencies as unverified (`micrometer-registry-prometheus`, `caffeine`, `springdoc 3.0.3`). All three resolved cleanly on the first build.

**Why I logged this:** a log that only records the catches overstates its own hit rate. Verification was applied uniformly, not selectively where the AI later turned out to be wrong. Reporting only the failures would be its own kind of dishonesty.

---

### 3 — Wrong major version of a library (silent runtime break)

**What happened:** AI suggested `springdoc-openapi-starter-webmvc-ui:2.6.0`. Boot 4 requires springdoc 3.x, because Boot 4 moved to Jackson 3 and broke the 2.x integration.

**Why it matters:** this would have **compiled fine** and failed at runtime. The compiler is not a correctness check for dependency versions.

---

### 4 — Flyway silently did nothing (the most instructive failure)

**What happened:** AI said to add `org.flywaydb:flyway-core`. Correct for Boot 3. In Boot 4 you need `spring-boot-starter-flyway`, because the autoconfiguration now lives in its own module.

**The symptom:** the app failed with `Schema validation: missing table [short_link]`. Flyway had produced **zero log output**. Not an error — silence.

**The diagnosis:** if Flyway had run and failed, there would be a Flyway error. If it had run and found no scripts, there would be a "no migrations found" message. Silence meant the auto-configuration never fired at all.

**Why it was silent:** `FlywayAutoConfiguration` is gated on `@ConditionalOnClass(Flyway.class)`. The class *was* on the classpath — but the autoconfiguration class itself lived in a module we did not have. **A condition inside a jar you do not have does not fail; it simply does not exist.**

**The generalization — this is the entry that mattered most:**

Three failures, one root cause, escalating in subtlety:

| Failure | Symptom | Loudness |
|---|---|---|
| Wrong artifact name | Compile error | Loud |
| Wrong major version | Runtime break | Quieter |
| Missing autoconfig module | Silent no-op | Invisible |

An AI's error rate is **not uniform**. It spikes wherever a framework changed its conventions after the training cutoff — and the model has no signal that it is in that zone. It is equally confident on both sides of the line.

**Rule adopted:** treat every dependency and configuration idiom on a post-cutoff framework version as unverified until proven by build resolution **and** observed runtime behaviour.

---

### 5 — AI over-applied its own recent failure pattern

**What happened:** the actuator log showed `Exposing 3 endpoints` when four were configured. The AI hypothesised "another missing Boot 4 module" — pattern-matching the three failures immediately preceding.

**It was wrong.** The real answer was already present in a log I had supplied: `MetricsContextCustomizerFactory$DisableMetricsExportContextCustomizer`. Spring's test framework deliberately disables metrics export in test contexts. Nothing was broken. In the production run, all four endpoints appeared.

**Lesson:** an AI that has just been wrong three times the same way will over-apply that explanation to the next anomaly. Recent failures become a prior that fires on unrelated evidence. **Test the hypothesis against the data — do not just re-ask the model.** The correcting evidence was already in my hands.

---

### 6 — I rejected the AI's recommendation

**The problem:** short codes are random. The app already serves `/actuator`, `/h2-console`, `/swagger-ui`. Eventually a random code will collide with a real path.

**Options:**
- A: namespace redirects under `/r/{code}` — collision impossible by construction
- B: keep the root path plus a reserved-word blocklist — shorter URLs

**AI recommended B.** I chose **A**.

**My reasoning:** B's failure mode is silent and deferred. Someone adds a root-level path months from now, forgets the blocklist, and an existing short link quietly starts resolving to the wrong place. Nothing warns anyone. Two extra characters is a trivial price for eliminating a class of bug permanently.

---

### 7 — Diagnostic error: I blamed the wrong system

**What happened:** a redirect returned 404 in Postman. I assumed my app was broken.

**It was not.** Postman follows redirects by default and displays only the *final* response. My app returned a correct 302; Postman followed it; `example.com` returned the 404 because the path I had invented did not exist there.

**The evidence was in front of me the whole time** — the response body said `<title>Example Domain</title>`. My app has no HTML templates and could not possibly have produced that page.

**Lesson:** when a tool aggregates several hops, confirm which hop produced the output you are reading.

---

### 8 — Verified the cache by absence of evidence

**What happened:** two redirects to the same code produced exactly **one** SELECT in the SQL log.

**Why this is the interesting one:** both HTTP responses were identical — 302, same headers, ~20ms. **A broken cache looks exactly the same from the outside.** A misconfigured `@Cacheable` fails silently: correct results, no error, no cache. The only way to verify it was to watch the layer *below* the one under test.

Confirmed independently via `/actuator/metrics/cache.gets` (1 hit, 1 miss).

**Lesson:** some correctness properties are invisible at the interface. Verifying them requires instrumenting a different layer.

---

### 9 — Tooling reports success on a dead application

**What happened:** `mvn spring-boot:run` printed `BUILD SUCCESS` at the bottom of a stack trace, three separate times — once for the Flyway failure, twice for port conflicts.

**Why:** the plugin reports whether it launched the JVM, not whether the app stayed up.

**Action:** stopped trusting the last line. Confirmed startup via the `Started UrlShortenerApplication in Xs` line instead.

---

### 10 — Bad configuration is silent, four times over

Four separate incidents, same shape:

| Incident | What happened |
|---|---|
| DevTools defaults | Silently enabled stack traces in error responses |
| Missing cache config | Silently gave an unbounded, no-TTL cache |
| Misplaced YAML block | `cache:` at root instead of under `spring:` — silently ignored |
| Spring's own resolver | Logged 404s at WARN despite my handler using DEBUG |

**None of these failed.** Each silently substituted a default I never chose.

**The generalization:** **security holes usually arrive as defaults, not as mistakes.** The unbounded cache and the leaked stack traces were not things I wrote — they were things I failed to override. Nothing in the framework announces "you are getting the default here."

---

### 11 — AI supplied code fragments that could not compile

**What happened:** twice, the AI gave a field declaration with a comment saying "add to constructor" rather than the complete file. Both times it failed to compile — a `final` field was declared but never assigned.

**What saved it:** every field in this codebase is `final`. That turned what would have been a runtime `NullPointerException` into a compile error.

**Lesson:** fragment-splicing is where the human silently absorbs the AI's integration work — and where errors creep in. AI output should be complete and runnable, or the human is doing hand-integration under the illusion of review.

---

### 12 — Three failing tests, three different root causes

This is the most important entry in this log.

Three AI-written tests failed. The symptom was identical each time: a red test. **The correct action was different every time.**

**Case A — the test was wrong.**
`UrlValidatorTest` asserted that `data:text/html,<script>alert(1)</script>` would be rejected with a message containing "not allowed". It was rejected — but by URI parsing, because `<` and `>` are illegal in a URI, before the scheme check ever ran.
The code was correct. The test over-specified: it asserted **which rule** fired rather than **that the input was rejected**.
→ **Fixed the test.**

**Case B — the code was wrong.**
`malformedJsonReturns400` expected 400 and got 500. Investigation: my `@ExceptionHandler(Exception.class)` catch-all runs *before* Spring's own resolver, so it was downgrading correct 400s into 500s — for malformed JSON, and also for 405, 415, and missing-parameter errors. Every one of those would page an on-call engineer for something a user did.
The AI wrote that handler **with a comment explaining why the catch-all was safe.** It wasn't.
→ **Fixed the code.**

**Case C — the test harness was wrong.**
Three `RedirectControllerTest` tests failed with `Wanted but not invoked... zero interactions with this mock` — which reads as "the controller does not publish events." It does. `ApplicationContext` itself implements `ApplicationEventPublisher` and is registered as a resolvable dependency, so `@MockitoBean` never took effect and the controller received the real context.
The failure message **actively misdirects**: it points at the production code when the fault is in the harness. The correcting evidence was in the MockMvc dump — status 302, correct headers, controller executed perfectly.
→ **Fixed the harness** (used Spring's `@RecordApplicationEvents` instead of mocking a framework-provided type).

**The lesson:**

> **A red test is a claim, not a verdict.**

Neither "trust the test" nor "trust the code" is a usable rule. The judgement is *which artifact encodes the intent*, and that has to be reasoned out fresh each time. "Make it green" would have produced three different wrong outcomes — including, in Case A, editing working security code to satisfy a bad assertion.

**And the sharper point:** the AI produced both the flawed exception handler **and** the test that caught it. The handler shipped with a confident comment justifying the design. The comment was wrong. **An AI's stated rationale is not evidence its code is correct** — only execution exposed it.

---

### 13 — Flagged uncertainty is only useful if acted on

**What happened:** the AI supplied `status().is(not(301))`, explicitly marking it as uncertain. It did not compile — `not()` is a Hamcrest matcher and does not compose with `status().is(int)`.

**The observation:** the AI correctly identified its own doubt and then supplied the code anyway. A flag shifts the checking work to the human; it does not remove it. Signalling uncertainty is not the same as resolving it.

---

## Summary: where AI helped and where it hurt

### Where AI added real value

- **Design reasoning.** The TOCTOU analysis, the 301-vs-302 trade-off, and the `CallerRunsPolicy` trap were all surfaced faster than I would have reached them alone.
- **Test breadth.** 73 tests in an afternoon, including edge cases (exactly 2048 vs 2049 characters, case-variant scheme bypasses) I would likely have skipped.
- **Naming the traps.** The `@Cacheable` and `@Async` self-invocation problem — a silent failure — was flagged before it was written, not after it broke.

### Where AI actively hurt

- **Anything version-specific.** 4 of 5 Boot 4 failures traced to a stale training cutoff. Every one built cleanly.
- **Confident wrong rationale.** The catch-all exception handler came with a comment explaining why it was safe. It was not.
- **Fragment-splicing.** Twice produced code that could not compile, because integration was left to me.
- **Pattern over-application.** After three failures with one cause, it applied that cause to an unrelated anomaly.

### What I would tell someone starting this

1. **Verify at the layer below.** The cache, the async executor, and the Flyway migration all *looked* fine from the response. Only the SQL log, the thread names, and the startup output told the truth.
2. **A failing test is a question, not an answer.** Three failures, three root causes, three different fixes.
3. **The model does not know what it does not know.** It is equally confident on both sides of its training cutoff. That is the whole problem in one sentence.
4. **Log the successes too.** A log of only the catches is a log that flatters itself.
