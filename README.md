# URL Shortener

Takes a long URL, gives you a short one. Open the short one, it sends you to the long one. Counts the clicks.

Built as an interview assignment for the AI-Proficient Software Engineer role.

```
https://www.example.com/some/very/long/path
                  ↓
    http://localhost:8080/r/DNd364w
```

---

## What you need

| | |
|---|---|
| Java | 17 or later |
| Maven | 3.9+ (or use the bundled `./mvnw`) |

Nothing else. The database is in-memory, so there's nothing to install or start.

---

## Running it

```bash
# build and run the tests
mvn clean verify

# start the app (holds the terminal)
mvn spring-boot:run
```

It comes up on **http://localhost:8080**.

Stop it with `Ctrl+C`. If the port is stuck from a previous run:

```bash
lsof -ti:8080 | xargs kill
```

**One thing worth knowing up front:** the database is H2 in-memory. Restart the app and every link you made is gone. That's deliberate — it keeps the setup to zero steps — but don't be surprised when your short code 404s after a restart.

---

## Trying it out

**Make a short link:**

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com"}'
```

```json
{
  "shortCode": "DNd364w",
  "shortUrl": "http://localhost:8080/r/DNd364w",
  "longUrl": "https://example.com",
  "createdAt": "2026-07-15T21:47:45.575613Z",
  "expiresAt": null
}
```

**Use it:**

```bash
curl -i http://localhost:8080/r/DNd364w
```

```
HTTP/1.1 302
Location: https://example.com
Cache-Control: no-store
```

Note there's no `-L`. Without it, curl shows you the 302 instead of following it. If you use `-L`, or test this in Postman with "follow redirects" on, you'll see whatever the destination returns and not our response — which is confusing if you're trying to check the redirect itself.

**Check the clicks:**

```bash
curl -s http://localhost:8080/api/v1/links/DNd364w/stats
```

```json
{
  "shortCode": "DNd364w",
  "totalClicks": 3,
  "clicksLast24h": 3,
  "uniqueVisitors": 1
}
```

Clicks are recorded on a background thread, so if you redirect and immediately check the stats you might see a count one short. That's the design, not a bug — the redirect never waits for the analytics write.

---

## The API

| | | |
|---|---|---|
| `POST` | `/api/v1/links` | Create a short link |
| `GET` | `/api/v1/links/{code}` | Link metadata |
| `GET` | `/api/v1/links/{code}/stats` | Click counts |
| `GET` | `/r/{code}` | The redirect (302) |

**Create request:**

```json
{
  "url": "https://example.com",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```

`expiresAt` is optional. Leave it out and the link never expires.

**Errors** come back as RFC 9457 problem+json:

```json
{
  "type": "https://api.schwab.com/errors/invalid-url",
  "title": "Invalid URL",
  "status": 400,
  "detail": "Scheme 'javascript' is not allowed; use http or https",
  "instance": "/api/v1/links",
  "timestamp": "2026-07-15T21:28:55.125136Z"
}
```

| Status | When |
|---|---|
| 400 | Bad URL, missing field, malformed JSON |
| 404 | Code doesn't exist, or the link expired |
| 503 | Couldn't generate a unique code (see limitations) |

Only `http` and `https` URLs are accepted. `javascript:`, `file:`, `data:` and everything else are rejected — see the architecture doc for why.

---

## Other URLs

| | |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| OpenAPI spec | http://localhost:8080/v3/api-docs |
| H2 console | http://localhost:8080/h2-console |
| Health | http://localhost:8080/actuator/health |
| Metrics | http://localhost:8080/actuator/metrics |
| Prometheus | http://localhost:8080/actuator/prometheus |

For the H2 console, the JDBC URL is `jdbc:h2:mem:urlshortener`, user `sa`, no password.

Swagger redirects `/swagger-ui.html` to `/swagger-ui/index.html`. Bookmark the second one.

---

## Tests

```bash
# everything
mvn clean verify

# one class
mvn test -Dtest=ShortLinkServiceTest
```

73 tests. The unit tests run in about a second; the integration test takes a few seconds because it boots the full context.

Coverage report lands at `target/site/jacoco/index.html` after a build:

```bash
open target/site/jacoco/index.html
```

87% instruction, 67% branch. See the testing doc for what that number does and doesn't tell you.

---

## Watching it work

Two things in this app are invisible from the HTTP response, so if you want to see them working you have to look at the logs.

**The cache.** Hit the same short link twice. The first request logs a SELECT; the second logs nothing. Both return an identical 302, which is exactly why you can't tell from the response.

**The async analytics.** The `insert into click_event` lines appear on threads named `analytics-1` and `analytics-2`, never on `nio-8080-exec-N`. That's how you know the database write isn't happening on the request thread.

SQL logging is already on in `application.yml`:

```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG
```

---

## Layout

```
src/main/java/com/schwab/urlShortener/
├── analytics/     click events, the background listener, IP hashing
├── api/           controllers, DTOs, the exception handler
├── config/        cache, async executor, clock
├── domain/        ShortLink, ClickEvent
├── repository/    Spring Data interfaces
└── service/       the actual rules

src/main/resources/
├── application.yml
└── db/migration/  Flyway scripts

docs/
├── ARCHITECTURE.md
├── THREE_SCENARIOS.md
├── TESTING_AND_LIMITATIONS.md
├── AI_USAGE_LOG.md
└── PROJECT_NOTES.md
```

---

## Docs

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — how it's put together and why
- **[THREE_SCENARIOS.md](THREE_SCENARIOS.md)** — greenfield, brownfield, ambiguous
- **[TESTING_AND_LIMITATIONS.md](TESTING_AND_LIMITATIONS.md)** — what's tested, what isn't, what's missing
- **[AI_USAGE_LOG.md](AI_USAGE_LOG.md)** — where AI helped, where it was wrong, and how I caught it
