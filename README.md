# AuraMath

A Spring Boot service that turns raw social-media post data (X / Twitter, YouTube, Reddit, Instagram)
into marketing intelligence. It fits **Hawkes self-exciting point processes** to author posting
timelines, computes a **Multi-platform Online Influence (MOI)** score, performs
**Stanford CoreNLP** aspect-level sentiment analysis, and exposes the results through a set
of REST endpoints that can be consumed by an ad-buying or campaign-planning service.

---

## Table of Contents

1. [Stack & Requirements](#stack--requirements)
2. [Running Locally](#running-locally)
3. [Configuration](#configuration)
4. [Backing Data Model](#backing-data-model)
5. [API Conventions](#api-conventions)
6. [Endpoint Reference](#endpoint-reference)
   - [Marketing Intelligence (`/v1`)](#1-marketing-intelligence-v1)
   - [User Profile (`/api/marketing`)](#2-user-profile-api)
   - [User Report (`/api/marketing/user-report`)](#3-user-report-api)
   - [Author Categorisation (`/api/marketing/users`)](#4-author-categorisation-api)
   - [Genre Marketing (`/api/marketing/genre`)](#5-genre-marketing-api)
   - [Political Marketing (`/api/marketing/party`)](#5a-political-marketing-api)
   - [Celebrity Marketing (`/api/marketing/celebrity`)](#5b-celebrity-marketing-api)
   - [Entity Report (`/api/marketing/entity-report`)](#5c-entity-report-api)
   - [Language Marketing (`/api/marketing/language`)](#5d-language-marketing-api)
   - [Movie Buffs (`/api/marketing/movie-buffs`)](#5e-movie-buffs-api)
   - [Celebrity Analytics (`/api/analytics/celebrity`)](#5f-celebrity-analytics-api)
   - [Narrative Novelty Scoring (`/api/marketing/narrative-novelty`)](#5g-narrative-novelty-scoring-api)
   - [Viral Seeds & Aspect Drivers (`/api/marketing`)](#6-viral-seeds--aspect-drivers)
   - [Top Spreaders (`/api/marketing/top-50-spreaders`)](#7-top-spreaders)
   - [Lookalike Discovery (`/api/marketing/find-lookalikes`)](#8-lookalike-discovery)
   - [Enrichment Admin (`/api/admin`)](#9-enrichment-admin-api)
   - [Diagnostic / Test (`/test`, `/api/test`)](#10-diagnostic--test-endpoints)
   - [Ask Engine (`/api/ask`)](#11-ask-engine-experimental)
   - [User Graph (`/api/graph`)](#12-user-graph-api)
   - [Movie Revenue Prediction Pipeline (scripts + `/api/admin`)](#13-movie-revenue-prediction-pipeline)
7. [Common Models](#common-models)
8. [Integration Recipes](#integration-recipes)
9. [Errors](#errors)
10. [Operational Notes](#operational-notes)

---

## Stack & Requirements

| Concern              | Choice                                    |
|----------------------|-------------------------------------------|
| Language             | Java 17                                   |
| Framework            | Spring Boot 2.7.5 (`spring-boot-starter-web`, `spring-boot-starter-jdbc`) |
| Build                | Maven (`com.lit.fire.flame:AuraMath:1.0-SNAPSHOT`) |
| Database             | PostgreSQL 12+ (JSONB columns required)   |
| NLP                  | Stanford CoreNLP 4.5.1                    |
| Math                 | Apache Commons Math3 3.6.1                |
| ML                   | Weka stable 3.8.6                         |
| JSON                 | Gson 2.10.1                               |
| Default port         | `8081`                                    |

Hard prerequisites:
- JDK 17 on `PATH`.
- A PostgreSQL instance reachable at the JDBC URL configured in `secrets.txt` containing the
  source post tables (`x_posts`, `youtube_comments`, `reddit_posts`, `instagram_posts`)
  and the enrichment outputs (`marketing_target_profiles`, `user_identity_link`,
  `author_categories`).

## Running Locally

```bash
# build
mvn clean package

# run (default port 8081)
mvn spring-boot:run
# or
java -jar target/AuraMath-1.0-SNAPSHOT.jar
```

The base URL used everywhere below is therefore `http://localhost:8081`.

## Configuration

`src/main/resources/application.properties` only sets the server port. Database credentials
live in `src/main/resources/secrets.txt` (classpath-loaded by `DataSourceConfig`):

```properties
# secrets.txt — fill in for your environment
db.url=jdbc:postgresql://<host>:<port>/<db>
db.user=<user>
db.password=<password>

# Ask Engine (com.lit.fire.flame.nlq) — LLM credentials. Optional; only needed once the
# Ask engine makes model calls. Never commit a real key.
anthropic.api.key=<your-anthropic-api-key>
```

Defaults if `secrets.txt` is absent: `jdbc:postgresql://localhost:5432/aura`, user `postgres`,
empty password. **Do not commit real secrets.**

The Ask engine reads `anthropic.api.key` from the same `secrets.txt`. It is optional: the engine
wires up without it, and the first LLM call fails with a clear configuration error if it is missing.
The active LLM provider is selected with the `aura.ask.llm-provider` property (default `claude`; only
`claude` is implemented today, and any other value fails fast at startup).

Scheduled jobs (enabled via `@EnableScheduling` on `AuraMathApplication`):
- `AuthorCategoryController.scheduledResync()` — re-runs `/api/marketing/users/sync` every
  24h, with a 5-minute startup delay.
- `MarketingEnrichmentScheduler.refresh()` — re-runs `MarketingEnrichmentEngine.enrichAndSave()`
  (same work as `POST /api/admin/run-enrichment`), then `UserEngagementRatingService` and
  `GraphPopulationService`, on a cron (default `03:30` daily, UTC — configure via
  `marketing.enrichment.cron` / `marketing.enrichment.zone`; set the cron to `-` to disable). A
  Postgres session-level advisory lock guards the run so only one instance rebuilds the shared
  tables when scaled to multiple replicas.

## Backing Data Model

The service reads from and writes to these PostgreSQL tables. Integrators do not need to
write to them directly, but the column names appear in responses.

| Table                       | Purpose                                                  |
|-----------------------------|----------------------------------------------------------|
| `x_posts`                   | X (Twitter) posts; has real `views_count`, `sentiment_score` |
| `youtube_comments`          | YouTube comments; no per-comment likes/views             |
| `reddit_posts`              | Reddit posts; `score` is net upvotes (likes proxy)       |
| `instagram_posts`           | Instagram posts; no impressions column                   |
| `marketing_target_profiles` | Enriched per-user rows (Hawkes α, MOI, top genres, handles) |
| `user_identity_link`        | Maps `global_user_id` → `normalized_author`              |
| `author_categories`         | Persisted category labels written by `/user-report` and `/users/sync` |
| `movies_data_collection`    | 544k-row global movie corpus (budget/revenue/cast/marketing telemetry) backing the standalone revenue-prediction pipeline, see [§13](#13-movie-revenue-prediction-pipeline) |
| `actors_data_collection`    | Per-movie cast/crew credits, self-joined for cast track-record factors in §13 |
| `data_sources`              | Feature 1: registry of per-entity source URLs (`sacnilk`/`kulfiy`/`fandango`/...) driving `scripts/collect_data.py`'s connectors |
| `factor_definitions`        | Feature 2: live replacement for the old hardcoded 80-factor catalogue — governs which columns the revenue model trains on. Read/written via the [Factor Registry Admin API](#13-movie-revenue-prediction-pipeline) |
| `movie_factor_values`       | Feature 2: generic per-movie EAV overflow table for factors with no dedicated column yet |

JSONB columns appearing in responses: `platform_handles`, `top_genres`, `peak_activity_times`.
They are unwrapped to proper JSON trees (not embedded strings) before the response is sent.

## API Conventions

- **Base path:** `http://localhost:8081`
- **Method:** `GET` unless noted. Bodies are JSON for `POST`s.
- **Content-Type:** `application/json` everywhere — no XML, no form encoding.
- **Auth:** none in code today. Put a gateway or reverse-proxy in front of the service if
  you need authentication.
- **Time zone:** Human-readable timestamps in `MarketingUserReportController` and the
  temporal audit are formatted in `Asia/Kolkata` (label `IST`). Database timestamps are
  read raw.
- **Path-param URL encoding:** Authors, genres, and keywords are passed as path parameters
  and may contain spaces or punctuation — always URL-encode them on the client side.
- **Filtering convention:** All filter query parameters are optional. Omit a parameter to
  remove that filter.

---

## Endpoint Reference   

### 1. Marketing Intelligence (`/v1`)

**`GET /v1/targets`**

Returns rows from `marketing_target_profiles` filtered by influence, genre, and platform.
Each row is enriched with a templated `Marketing Tip` string.

Query params (all optional):

| Name              | Type   | Default | Description                                              |
|-------------------|--------|---------|----------------------------------------------------------|
| `genre`           | string | —       | Substring match against `top_genres` JSONB column.       |
| `minInfluenceScore` | number | `0.0` | Minimum `influence_rank` (Hawkes α stored as rank).      |
| `platform`        | string | —       | Substring match against `platform_handles` JSONB column. |

**Sample response (200):**

```json
[
  {
    "global_user_id": "u_182374",
    "tribe_label": "Cinephile-Critic",
    "influence_rank": 0.84,
    "moi_score": 12.7,
    "platform_handles": {"primary_platform": "x", "by_platform": {"x": {"profile_url": "https://x.com/..."}}},
    "top_genres": {"thriller": 0.91, "horror": 0.72},
    "peak_activity_times": {"hours": [21, 22, 23]},
    "Marketing Tip": "Target on x during {\"hours\":[21,22,23]} with visual-heavy ads"
  }
]
```

---

### 2. User Profile API

**`GET /api/marketing/user-profile/{globalUserId}`**

Resolves a global user ID to a `normalized_author`, then aggregates per-platform engagement
stats live from each source table.

Path params:

| Name           | Description                                     |
|----------------|-------------------------------------------------|
| `globalUserId` | The internal identifier stored in `user_identity_link`. |

**Sample response (200):**

```json
{
  "globalUserId": "u_182374",
  "normalizedAuthor": "janedoe",
  "xStats": { "totalLikes": 12034, "totalViews": 982121, "postCount": 87 },
  "youtubeStats": { "totalReplies": 211, "commentCount": 39 },
  "redditStats": { "totalScore": 4501, "averageScore": 142.3, "postCount": 31 },
  "instagramStats": {
    "mediaTypeBreakdown": { "IMAGE": 42, "VIDEO": 18, "CAROUSEL_ALBUM": 5 },
    "preferredMediaType": "IMAGE"
  }
}
```

`404 Not Found` is returned if `user_identity_link` has no row for `globalUserId`.

---

### 3. User Report API

**`GET /api/marketing/user-report/{author}`**

The flagship analyst-facing endpoint. Runs the full Hawkes audit on the author's post
timeline across every platform, then produces a structured marketing brief. As a side
effect it upserts the derived category labels into `author_categories`.

Sections in the response:

| Section                    | What it tells the marketer                                             |
|----------------------------|-----------------------------------------------------------------------|
| `userProfile`              | Platforms used, total posts, first/last seen, observation window, influence tier. |
| `engagementProfile`        | Posting style (`Steady` / `Reactive` / `Burst` / `Power Burst`), peak hours (top 3, in IST), busiest day-of-week, longest burst summary. |
| `topicIntelligence`        | Per-keyword breakdown: tone distribution, dominant tone, average excitation spike, excitation profile. |
| `marketingRecommendations` | Primary channel, best posting hour window, campaign type, audience classification, content strategy, plain-English actionable advice. |
| `redFlags`                 | Risk list (severity: LOW / MEDIUM / HIGH).                            |
| `opportunityFlags`         | Specific openings (e.g. `Movie Buff`, `Keyword Anchor Window`). |

**Empty-history response (200):**

```json
{ "author": "ghost_user", "message": "No post history found — cannot generate a report" }
```

**Sample populated response (200) (abridged):**

```json
{
  "generatedAt": "2026-05-16T09:21:47 IST",
  "userProfile": {
    "author": "janedoe",
    "activePlatforms": ["x", "reddit"],
    "totalPosts": 118,
    "firstSeen": "2025-12-08T11:04:00 IST",
    "lastSeen":  "2026-05-15T22:43:00 IST",
    "observationSpanDays": 158.5,
    "averagePostsPerDay": 0.7,
    "influenceTier": "Amplifier",
    "influenceTierExplained": "Branching ratio 0.67 — reliable content amplifier. ..."
  },
  "engagementProfile": {
    "postingStyle": "Burst Poster",
    "distinctBurstEvents": 4,
    "averageGapInsideBurstMinutes": 3.42,
    "peakActivityWindows": ["21:00–22:00 IST (24 posts)", "22:00–23:00 IST (18 posts)"],
    "mostActiveDayOfWeek": "FRIDAY (29 posts)",
    "longestBurst": {
      "keyword": "Avengers", "startTime": "2026-03-04T20:11:00 IST",
      "durationMinutes": 26.3, "postCount": 12, "peakExcitationSpike": 0.184,
      "readableDescription": "12 posts about 'Avengers' in 26.3 minutes — peak excitation 0.18"
    }
  },
  "marketingRecommendations": {
    "primaryChannel": "X (Twitter)",
    "bestTimeToEngage": "21:00–22:00 IST — peak activity window",
    "campaignType": "Reactive Amplification Campaign — ...",
    "audienceClassification": "Critical Power Influencer — vocal critic with high amplification potential. High risk, high reward.",
    "amplificationPotential": "MEDIUM — reliable amplifier; expect ~0.7x organic reach per interaction",
    "estimatedReachMultiplier": "~0.7x per post (branching ratio 0.67)",
    "contentTriggers": ["Avengers"],
    "contentStrategy": "User posts critically about 'Avengers'. Lead with solutions, ...",
    "actionableAdvice": "1. POST TIMING: ... 2. PLATFORM: ... 3. TONE: ... 4. TRIGGER: ..."
  },
  "redFlags":         [ { "flag": "Burst Saturation Risk", "severity": "HIGH", "detail": "..." } ],
  "opportunityFlags": [ { "opportunity": "High-Velocity Amplifier", "detail": "..." } ]
}
```

**Influence tier mapping (branching ratio α/β):**

| Range           | Tier          |
|-----------------|---------------|
| `≥ 0.8`         | `Viral Node`  |
| `0.6 – 0.8`     | `Amplifier`   |
| `0.3 – 0.6`     | `Participant` |
| `< 0.3`         | `Observer`    |

---

### 4. Author Categorisation API

These endpoints back the persisted `author_categories` table.

**`GET /api/marketing/users`**

List categorised authors. All query params optional — combine as needed.

| Param                    | Example value         |
|--------------------------|----------------------|
| `audienceClassification` | `Movie Buff`         |
| `influenceTier`          | `Viral Node`         |
| `postingStyle`           | `Power Burst Poster` |
| `dominantTone`           | `positive`           |
| `primaryPlatform`        | `x`                  |

Response:

```json
{
  "filtersApplied": { "audience_classification": "Movie Buff" },
  "totalUsers": 3,
  "users": [ { "author": "janedoe", "influence_tier": "Amplifier", "..." } ]
}
```

**`GET /api/marketing/users/categories`**

Returns the distinct values present in each categorical column — useful to populate
filter dropdowns in a UI.

```json
{
  "audience_classification": ["Movie Buff", "Critical Power Influencer", "Neutral Informer"],
  "influence_tier":          ["Viral Node", "Amplifier", "Participant", "Observer"],
  "posting_style":           ["Steady Poster", "Burst Poster", "Power Burst Poster"],
  "dominant_tone":           ["positive", "negative", "neutral"],
  "primary_platform":        ["x", "reddit", "youtube", "instagram"]
}
```

**`POST /api/marketing/users/sync`**

Walks every author with at least one valid scored post, re-categorises them, and upserts
into `author_categories`. Heavy — can take minutes on a full DB. Body is ignored.

Response:

```json
{
  "rule": "Only authors with total_posts > 5 are categorised",
  "totalAuthorsScanned": 412,
  "upserted": 287,
  "skippedFewPosts": 98,
  "skippedEmpty": 23,
  "staleRowsPurged": 4,
  "failed": 0
}
```

This endpoint is also fired automatically every 24h by the scheduler.

---

### 5. Genre Marketing API

Flat-JSON endpoints designed for an ad-buying dashboard. The `{genre}` path
parameter accepts case-insensitive labels (e.g. `horror`, `Action`, `thriller`).

**`GET /api/marketing/genre`**

Lists every movie genre the classifier can score against. Use this to discover
valid `{genre}` path values for the three endpoints below.

```json
{
  "totalGenres": 12,
  "genres": [
    { "genre": "Horror",   "keywordCount": 12 },
    { "genre": "Sci-Fi",   "keywordCount": 12 },
    { "genre": "Thriller", "keywordCount":  9 }
  ]
}
```

**`GET /api/marketing/genre/{genre}/posts[?platform=<platform>&limit=<n>&offset=<n>]`**

Individual posts classified into `{genre}`, newest first — unlike the endpoints below, this
returns the actual post records instead of aggregated audience/spreader stats. Genre membership
has no stored column, so matches are computed live with `GenreClassifier` against the 1000 most
recent posts per platform (same live-scan-with-a-cap approach used elsewhere for unbounded
per-request scans); `totalPosts` reflects matches within that scanned window, not the full
historical corpus.

| Param      | Type    | Default | Description                                                   |
|------------|---------|---------|-----------------------------------------------------------------|
| `platform` | string  | —       | Restrict to one of `x` / `youtube` / `reddit` / `instagram`. `400` on an unrecognized value. |
| `limit`    | integer | `50`    | Page size, 1–200. `400` outside that range.                    |
| `offset`   | integer | `0`     | Rows to skip, applied after sorting newest-first.               |

```json
{
  "genre": "horror",
  "limit": 50,
  "offset": 0,
  "totalPosts": 214,
  "scanNote": "classified live against the 1000 most recent posts per platform",
  "posts": [
    {
      "platform": "reddit",
      "postId": "18f2a91",
      "author": "janedoe",
      "content": "This new horror movie had the whole theater screaming...",
      "permalink": "https://reddit.com/r/movies/comments/18f2a91",
      "createdAt": "2026-05-15T22:43:00.000+00:00",
      "sentimentScore": 78.0,
      "sentimentCategory": "positive",
      "engagementMetric": 211,
      "genreScore": 3.0
    }
  ]
}
```

**`GET /api/marketing/genre/{genre}/potential-viewers`**

Users whose `top_movie_genres[{genre}]` > `1.0`, sorted by predicted conversion probability
`p_conv = sigmoid(genre_interest_score * influence_rank)`. The genre weight sums the
classifier's per-post scores, and one matched post contributes at least `1.0`, so the
threshold requires more than a single post's worth of genre signal.

```json
{
  "genre": "horror",
  "threshold": 1.0,
  "scoringModel": "p_conv = 1 / (1 + exp(-(genre_interest_score * influence_rank)))",
  "totalViewers": 27,
  "viewers": [
    {
      "global_user_id": "u_182374",
      "tribe_label": "Cinephile-Critic",
      "platform_handles": { "primary_platform": "x", "by_platform": { "x": {"profile_url": "..."} } },
      "peak_activity_times": { "hours": [21,22,23] },
      "genre_interest_score": 3.0,
      "influence_rank": 0.84,
      "moi_score": 12.7,
      "p_conv": 0.926
    }
  ]
}
```

**`GET /api/marketing/genre/{genre}/super-spreaders`**

Top **50** users for the genre by Hawkes α (stored as `influence_rank`).

```json
{
  "genre": "horror",
  "limit": 50,
  "rankingMetric": "hawkes_alpha (stored as influence_rank)",
  "totalSpreaders": 50,
  "spreaders": [ { "global_user_id": "...", "hawkes_alpha": 0.92, "..." } ]
}
```

**`GET /api/marketing/genre/{genre}/channel-strategy`**

Per-platform reach and a copy-ready `headline` string. Reach metric is platform-specific:

| Platform   | Reach proxy     |
|------------|-----------------|
| X          | `views_count`   |
| YouTube    | `likes_count`   |
| Reddit     | `num_comments`  |
| Instagram  | `like_count`    |

```json
{
  "genre": "horror",
  "audienceSize": 412,
  "reachMetric": {"X":"views_count","YouTube":"like_count","Reddit":"num_comments","Instagram":"like_count"},
  "topChannel": "Reddit",
  "headline": "horror fans are 3.2x more active on Reddit than Instagram.",
  "channels": [
    { "platform": "Reddit",   "reach": 28104, "postCount": 211, "relative_strength": 1.0 },
    { "platform": "X",        "reach": 19034, "postCount": 184, "relative_strength": 0.677 },
    { "platform": "Instagram","reach":  8821, "postCount":  92, "relative_strength": 0.314 },
    { "platform": "YouTube",  "reach":  3120, "postCount":  47, "relative_strength": 0.111 }
  ]
}
```

---

### 5a. Political Marketing API

Party-scoped equivalents of the Genre endpoints. The `{party}` path parameter
is matched (case-insensitive) against `entity_keywords.keyword` for rows where
`category = 'media.politics'`. There is no precomputed per-user party-affinity
column, so each call aggregates the source post tables on the fly — slightly
slower than the genre endpoints, but uses only data already collected.

**`GET /api/marketing/party`**

Lists every party known to `entity_keywords` (grouped by `entity_id`, joined to
`managed_entities` for the canonical name). `state` is taken from
`entity_keywords.state`; `keywords` lists all keyword variants for that entity.

```json
{
  "category":     "media.politics",
  "totalParties": 2,
  "parties": [
    { "entityId": 41, "name": "DMK", "type": "POLITICAL_PARTY", "state": "Tamil Nadu", "keywords": ["DMK","dmk"] },
    { "entityId": 42, "name": "BJP", "type": "POLITICAL_PARTY", "state": "Tamil Nadu", "keywords": ["BJP","bjp"] }
  ]
}
```

**`GET /api/marketing/party/{party}/potential-voters`**

Users who have posted with `keyword = {party}` on any platform. The affinity
score is a soft-saturated function of post count and total engagement; users
are sorted by `p_conv = sigmoid(affinity_score * influence_rank)`.

```json
{
  "party": "DMK",
  "scoringModel": "p_conv = 1 / (1 + exp(-(affinity_score * influence_rank)))",
  "totalVoters": 312,
  "voters": [
    {
      "global_user_id": "@stalin_official",
      "tribe_label": "Political-Engager",
      "platform_handles": { "primary_platform": "x", "by_platform": {"x": {"profile_url": "..."}} },
      "peak_activity_times": { "hours": [19, 20, 21] },
      "post_count": 47,
      "total_engagement": 18234,
      "affinity_score": 0.91,
      "influence_rank": 0.78,
      "moi_score": 14.2,
      "p_conv": 0.671
    }
  ]
}
```

**`GET /api/marketing/party/{party}/posts[?platform=<platform>&limit=<n>&offset=<n>]`**

Individual posts (not aggregated stats) whose `keyword` column matches `{party}`, newest first.
Reads straight from the four source tables (`x_posts`, `youtube_comments`, `reddit_posts`,
`instagram_posts`) with the same `keyword ILIKE` case-insensitive match `potential-voters` uses.
`totalPosts` is the full match count across all platforms, not just the current page.

| Param      | Type    | Default | Description                                                   |
|------------|---------|---------|-----------------------------------------------------------------|
| `platform` | string  | —       | Restrict to one of `x` / `youtube` / `reddit` / `instagram`. `400` on an unrecognized value. |
| `limit`    | integer | `50`    | Page size, 1–200. `400` outside that range.                    |
| `offset`   | integer | `0`     | Rows to skip, applied after sorting newest-first.               |

```json
{
  "party": "DMK",
  "limit": 50,
  "offset": 0,
  "totalPosts": 312,
  "posts": [
    {
      "platform": "x",
      "postId": "1782934012",
      "author": "@stalin_official",
      "content": "DMK's welfare schemes have transformed lives across Tamil Nadu...",
      "permalink": "https://x.com/stalin_official/status/1782934012",
      "createdAt": "2026-05-15T22:43:00.000+00:00",
      "sentimentScore": 82.0,
      "sentimentCategory": "positive",
      "likes": 4102,
      "comments": 318,
      "views": 182034
    }
  ]
}
```

**`GET /api/marketing/party/{party}/super-spreaders`**

Top **50** users who posted about `{party}`, ranked by Hawkes α
(`influence_rank`).

```json
{
  "party": "DMK",
  "limit": 50,
  "rankingMetric": "hawkes_alpha (stored as influence_rank)",
  "totalSpreaders": 50,
  "spreaders": [ { "global_user_id": "...", "hawkes_alpha": 0.93, "post_count": 22, "...": "..." } ]
}
```

**`GET /api/marketing/party/{party}/channel-strategy`**

Per-platform reach for the party keyword, with copy-ready `headline`. Reach
proxies:

| Platform   | Reach proxy     |
|------------|-----------------|
| X          | `views_count`   |
| YouTube    | `likes_count`   |
| Reddit     | `num_comments`  |
| Instagram  | `like_count`    |

```json
{
  "party": "DMK",
  "audienceSize": 312,
  "reachMetric": {"X":"views_count","YouTube":"likes_count","Reddit":"num_comments","Instagram":"like_count"},
  "topChannel": "X",
  "headline": "DMK supporters is 2.4x more active on X than YouTube.",
  "channels": [ { "platform": "X", "reach": 184321, "postCount": 412, "relative_strength": 1.0 } ]
}
```

---

### 5b. Celebrity Marketing API

Mirror of the political endpoints but filtered to `category = 'media.celebrity'`.
Audience and spreaders are renamed `fans` / `superFans`.

**`GET /api/marketing/celebrity`**

```json
{
  "category": "media.celebrity",
  "totalCelebrities": 14,
  "celebrities": [
    { "entityId": 33, "name": "Sai Dharam Tej", "type": "CELEBRITY", "industry": "Tollywood",
      "keywords": ["SaiDharamTej","saidharamtej"] },
    { "entityId": 34, "name": "Surya", "type": "CELEBRITY", "industry": "Kollywood, Tollywood",
      "keywords": ["Surya","surya"] }
  ]
}
```

**`GET /api/marketing/celebrity/{celebrity}/potential-fans`**

Same shape as `/party/{party}/potential-voters` — `voters` is renamed to `fans`,
`totalVoters` to `totalFans`. All other fields identical.

**`GET /api/marketing/celebrity/{celebrity}/posts[?platform=<platform>&limit=<n>&offset=<n>]`**

Same shape and query params as [`/party/{party}/posts`](#5a-political-marketing-api) — individual
post records matching the celebrity keyword, newest first, with `party` replaced by `celebrity`.

**`GET /api/marketing/celebrity/{celebrity}/super-fans`**

Top 50 users who posted about the celebrity keyword, ranked by Hawkes α. Same
shape as the party spreaders endpoint with `spreaders` → `superFans` and
`totalSpreaders` → `totalSuperFans`.

**`GET /api/marketing/celebrity/{celebrity}/channel-strategy`**

Same shape as `/party/{party}/channel-strategy` with `party` replaced by
`celebrity`. Reach proxies are identical.

---

### 5c. Entity Report API

The entity-scoped counterpart to the [User Report API](#3-user-report-api). Where the
user report audits a single author, this audits an **entity** — a `managed_entities`
row (celebrity, party, genre, …) — by aggregating every post that matches **any** of the
entity's tracked keywords (`entity_keywords`, case-insensitive) across all platforms, then
running the same Hawkes audit over the combined timeline. Read-only: unlike the user report
it writes no category labels.

The entity is identified by `managed_entities.id`; use any listing endpoint
(`/api/marketing/celebrity`, `/party`, `/genre`) to discover valid ids and their keywords.

**Two endpoints return the identical payload:**

| Endpoint                                      | Intended use                                                        |
|-----------------------------------------------|---------------------------------------------------------------------|
| `GET /api/marketing/entity-report/{entityId}` | Shareable report — e.g. to show a prospect why the product is worth it. |
| `GET /api/marketing/entity/{entityId}/report` | In-app view a signed-in user opens for any entity of their choice.  |

Both are public, consistent with the rest of `/api/marketing`.

**`GET /api/marketing/entity-report/{entityId}/pdf`**

Renders the identical shareable-report payload as a polished, sales-oriented PDF — the artefact
handed to a prospect — via `EntityReportPdfRenderer`. Returns `Content-Type: application/pdf` with
`Content-Disposition: inline` (opens directly in a browser) and a filename derived from the
entity's name, e.g. `Taylor-Swift-intelligence-report.pdf`. Returns `404` with a plain-text body
if the entity is unknown or has no scored history (same conditions as the JSON endpoints above).

Sections in the response:

| Section                    | What it tells the marketer                                             |
|----------------------------|-----------------------------------------------------------------------|
| `entityProfile`            | Tracked keywords, active platforms, total posts, distinct audience size, first/last seen, observation window, virality tier. |
| `conversationProfile`      | Branching ratio (α/β), distinct burst events, peak hours (top 3, in IST), busiest day-of-week, longest burst summary. |
| `topicIntelligence`        | Per-keyword breakdown (sorted by mentions): tone distribution, dominant tone, average sentiment, average excitation spike, excitation profile. |
| `audienceSentiment`        | Overall tone breakdown, dominant tone, net sentiment (−1…1) and label.|
| `channelStrategy`          | Per-platform post counts and share of conversation, led-by headline.  |
| `topAdvocates`             | Top 10 voices in the conversation, ranked by Hawkes α, with platform handles. |
| `marketingRecommendations` | Primary channel, best engagement window, campaign type, addressable audience, content strategy, plain-English actionable advice. |
| `redFlags`                 | Risk list (severity: LOW / MEDIUM / HIGH).                            |
| `opportunityFlags`         | Specific openings (e.g. `High-Velocity Topic`, `Keyword Anchor Window`). |

**Unknown entity id (200):**

```json
{ "entityId": "99999", "message": "No entity found for this id" }
```

**Entity with no scored history (200):**

```json
{ "entityId": "42", "name": "Some Movie", "trackedKeywords": ["..."],
  "message": "No scored post history found for this entity — cannot generate a report" }
```

**Sample populated response (200) (abridged):**

```json
{
  "generatedAt": "2026-06-06T18:30:11 IST",
  "entityProfile": {
    "entityId": "21",
    "name": "Madhavan",
    "type": "CELEBRITY",
    "trackedKeywords": ["Maddy", "Madhavan", "RMadhavan", "maddy", "madhavan", "rmadhavan"],
    "activePlatforms": ["youtube", "x"],
    "totalPosts": 1490,
    "audienceSize": 2336,
    "firstSeen": "2024-05-13T21:20:58 IST",
    "lastSeen":  "2026-05-04T19:13:16 IST",
    "observationSpanDays": 720.9,
    "averagePostsPerDay": 2.1,
    "viralityTier": "Active Conversation",
    "viralityTierExplained": "Branching ratio 0.39 — an active but measured conversation. ..."
  },
  "conversationProfile": {
    "branchingRatio": 0.3948,
    "amplificationExplained": "Each post about this entity triggers ~0.39 organic follow-up posts on average ...",
    "distinctBurstEvents": 53,
    "peakActivityWindows": ["22:00–23:00 IST (115 posts)", "20:00–21:00 IST (110 posts)"],
    "mostActiveDayOfWeek": "SUNDAY (248 posts)",
    "longestBurst": {
      "keyword": "Madhavan", "startTime": "2026-04-12T17:05:30 IST",
      "durationMinutes": 34.2, "postCount": 13, "peakExcitationSpike": 0.189,
      "readableDescription": "13 posts about 'Madhavan' in 34.2 minutes — peak excitation 0.19"
    }
  },
  "topicIntelligence": [
    { "keyword": "Madhavan", "totalMentions": 719, "burstsTriggered": 29,
      "contentCategory": "media.celebrity", "dominantTone": "neutral",
      "averageSentimentScore": 70.8, "averageExcitationSpike": 0.01,
      "excitationProfile": "LOW — mentions are scattered ... Tone: neutral." }
  ],
  "audienceSentiment": {
    "toneBreakdown": { "negative": 37, "neutral": 958, "positive": 495 },
    "dominantTone": "neutral", "netSentiment": 0.31, "sentimentLabel": "Predominantly Positive"
  },
  "channelStrategy": {
    "topChannel": "YouTube",
    "headline": "Conversation is led by YouTube — focus campaign spend there first.",
    "channels": [
      { "platform": "YouTube", "postCount": 1325, "share": 0.889 },
      { "platform": "X (Twitter)", "postCount": 165, "share": 0.111 }
    ]
  },
  "topAdvocates": [
    { "global_user_id": "@BijiBiji-l6o", "tribe_label": "Tribe_4",
      "hawkes_alpha": 1.0, "post_count": 3, "total_engagement": 0, "platform_handles": { "...": "..." } }
  ],
  "marketingRecommendations": {
    "primaryChannel": "YouTube",
    "bestTimeToEngage": "22:00–23:00 IST — peak conversation window",
    "campaignType": "Targeted Engagement Campaign — ...",
    "amplificationPotential": "LOW-MEDIUM — moderate spread; best paired with paid amplification",
    "estimatedReachMultiplier": "~0.4x per seeded post (branching ratio 0.39)",
    "addressableAudience": "2336 distinct authors are already talking about this entity",
    "contentTriggers": ["Madhavan"],
    "contentStrategy": "Conversation about 'Madhavan' is informational. ...",
    "actionableAdvice": "1. TIMING: ... 2. PLATFORM: ... 3. TONE: ... 4. TRIGGER: ..."
  },
  "redFlags":         [ { "flag": "Negative Sentiment Majority", "severity": "HIGH", "detail": "..." } ],
  "opportunityFlags": [ { "opportunity": "Keyword Anchor Window", "detail": "..." } ]
}
```

**Virality tier mapping (branching ratio α/β):**

| Range       | Tier                  |
|-------------|-----------------------|
| `≥ 0.8`     | `Viral Topic`         |
| `0.6 – 0.8` | `Trending`            |
| `0.3 – 0.6` | `Active Conversation` |
| `< 0.3`     | `Niche`               |

---

### 5d. Language Marketing API

Flat-JSON endpoint for the language-affinity audience of language-tagged movies. The
`{language}` path parameter is case-insensitive (e.g. `tamil`, `Tamil`, `TAMIL`).

**`GET /api/marketing/language/{language}/posts[?platform=<platform>&limit=<n>&offset=<n>]`**

Individual posts (not aggregated per-user stats) for `MOVIE` entities tagged with `{language}`,
newest first. Reads straight off the `mentions` table joined to `mention_entities`/
`managed_entities` — `mentions.content`/`permalink` already hold the post text and link per row,
so unlike `/users` below this needs no lookup back into the four platform tables or
`user_identity_link`. `platform` here filters on `mentions.platform` (case-insensitive), which is
stored uppercase (`X`/`YOUTUBE`/`REDDIT`/`INSTAGRAM`) but accepts either case.

| Param      | Type    | Default | Description                                                   |
|------------|---------|---------|-----------------------------------------------------------------|
| `platform` | string  | —       | Restrict to one platform (case-insensitive).                    |
| `limit`    | integer | `50`    | Page size, 1–200. `400` outside that range.                    |
| `offset`   | integer | `0`     | Rows to skip, applied after sorting newest-first.               |

```json
{
  "language": "Tamil",
  "limit": 50,
  "offset": 0,
  "totalPosts": 148,
  "posts": [
    {
      "platform": "X",
      "postId": 91423,
      "author": "@filmy_fan",
      "content": "Vikram is a masterclass in action filmmaking, Tamil cinema at its best!",
      "permalink": "https://x.com/filmy_fan/status/91423",
      "createdAt": "2026-05-15T22:43:00.000+00:00",
      "sentimentScore": 84.0,
      "sentimentCategory": "positive",
      "movie": "Vikram"
    }
  ]
}
```

**`GET /api/marketing/language/{language}/users`**

Every distinct user with a mention linked to a `managed_entities` row where
`type = 'MOVIE'` and `language` matches `{language}`. `author` → `global_user_id`
resolution goes through `user_identity_link` (normalize + lookup, same as the
Engagement Rating and Lookalike Discovery pipelines); mentions whose author has no
resolved identity are skipped. `engagement_rating` / `tribe_label` / `platform_handles`
are enriched from `marketing_target_profiles` via a left-join — users are never dropped
for missing enrichment, those fields are `null` instead. Sorted by `engagement_rating`
descending, with unenriched (`null`) users last. An unknown language returns an empty
`users` list, not a `404`.

```json
{
  "language": "Tamil",
  "totalUsers": 3,
  "users": [
    {
      "global_user_id": "user-9f2c1e3a-...",
      "mention_count": 14,
      "distinct_movies_mentioned": 4,
      "engagement_rating": 87.5,
      "tribe_label": "Cinephile-Critic",
      "platform_handles": { "primary_platform": "x", "by_platform": { "x": {"profile_url": "..."} } }
    },
    {
      "global_user_id": "user-4b7a08d1-...",
      "mention_count": 2,
      "distinct_movies_mentioned": 2,
      "engagement_rating": null,
      "tribe_label": null,
      "platform_handles": null
    }
  ]
}
```

**`GET /api/marketing/language/{language}/movie/{movieName}/users`**

Same join as above, additionally filtered to `managed_entities` rows whose `name`
matches `{movieName}` exactly (case-insensitive; no wildcard is added server-side, so
`{movieName}` must match the full `managed_entities.name` value). `mention_count` and
`distinct_movies_mentioned` are scoped to that movie-filtered set. Each user entry
also carries `mentions_on_this_movie` and `average_sentiment_score`, computed from
`mentions.sentiment_score` for that user on that movie, restricted to the same
`sentiment_score BETWEEN 1 AND 100` bounds check used by the Genre Marketing API —
mentions with an out-of-range or missing sentiment score still count toward
`mention_count` but are excluded from the average. If `{movieName}` doesn't resolve
to any `managed_entities` row for `{language}`, the response is `totalUsers: 0` with
an empty `users` list, not an error.

```json
{
  "language": "Tamil",
  "movie": "Vikram",
  "totalUsers": 2,
  "users": [
    {
      "global_user_id": "user-9f2c1e3a-...",
      "mention_count": 6,
      "distinct_movies_mentioned": 1,
      "engagement_rating": 87.5,
      "tribe_label": "Cinephile-Critic",
      "platform_handles": { "primary_platform": "x", "by_platform": { "x": {"profile_url": "..."} } },
      "mentions_on_this_movie": 5,
      "average_sentiment_score": 78.4
    },
    {
      "global_user_id": "user-4b7a08d1-...",
      "mention_count": 1,
      "distinct_movies_mentioned": 1,
      "engagement_rating": null,
      "tribe_label": null,
      "platform_handles": null,
      "mentions_on_this_movie": 0,
      "average_sentiment_score": null
    }
  ]
}
```

---

### 5e. Movie Buffs API

**`GET /api/marketing/movie-buffs/{keyword}`**

Intersects the global `author_categories` classification (see [Author Categorisation
API](#4-author-categorisation-api)) with per-keyword post activity: returns every author already
labelled `audience_classification = 'Movie Buff'` (positive tone, high branching ratio) who
has also posted about `{keyword}` on any platform. The classification itself is not keyword-aware,
so this endpoint does the intersection at query time via `EntityMarketingService.movieBuffs`.

An empty `movieBuffs` list is legitimate — it means no categorised movie buff has posted about
this keyword yet (e.g. a new keyword, or `/api/marketing/users/sync` hasn't run).

```json
{
  "keyword": "Avengers",
  "totalMovieBuffs": 2,
  "movieBuffs": [
    {
      "author": "janedoe",
      "audienceClassification": "Movie Buff",
      "influenceTier": "Amplifier",
      "postingStyle": "Burst Poster",
      "dominantTone": "positive",
      "primaryPlatform": "x",
      "branchingRatio": 0.67,
      "totalPosts": 118,
      "keywordPostCount": 14,
      "keywordEngagement": 18234
    }
  ]
}
```

Sorted by `branchingRatio` descending, then `keywordEngagement` descending.

---

### 5f. Celebrity Analytics API

Predictive brand/endorsement analytics, active only for managed entities of type `CELEBRITY`.
Implemented by the pure `CelebrityMetricsModel` fed with signals gathered by
`CelebrityAnalyticsService` (Hawkes self-excitation and sentiment via `EntityIntelService`/
`HawkesAuditService`; reach, engagement, fan-base size and advocate strength via
`EntityMarketingService`) — the same aggregations the rest of the marketing stack uses, so these
analytics never drift from the underlying intelligence.

**`GET /api/analytics/celebrity`**

Lists managed entities of type `CELEBRITY` with their tracked keyword sets — use this to discover
valid `{entityId}` values.

```json
{
  "entityType": "CELEBRITY",
  "totalCelebrities": 14,
  "celebrities": [
    { "entityId": "33", "name": "Sai Dharam Tej", "keywords": ["SaiDharamTej", "saidharamtej"] }
  ]
}
```

**`GET /api/analytics/celebrity/{entityId}`**

Full analytics payload for one celebrity. Returns `404` if the id is unknown or the entity is not
of type `CELEBRITY`, or a `200` with a `message` (and `trackedKeywords`) if it has no scored post
history yet.

Sections in the response:

| Section             | What it tells the marketer                                                          |
|----------------------|--------------------------------------------------------------------------------------|
| `celebrity`          | Tracked keywords, active platforms, total posts, fan base size, observation window.  |
| `headlineMetrics`    | `predictedBrandValueUsd` (+ display string), `socialMediaReachValue`, `fanEngagementValue`, `endorsementScore` (0–100). |
| `keyMetricsPercent`  | `socialMediaInfluence`, `brandPower`, `fanLoyalty`, `controversyRisk` — each 0–100 with a coarse band (`Very Low`…`Very High`). |
| `reachBreakdown` / `engagementBreakdown` | Per-platform reach/engagement, same shape as the Genre/Party channel-strategy endpoints. |
| `sentiment`          | Tone counts, net sentiment + label, sentiment volatility, negative-burst share.      |
| `topAdvocates`       | Top 10 voices in the conversation (same shape as Entity Report's `topAdvocates`).    |
| `scoreDrivers`       | The individual [0,1] sub-scores (`reachScore`, `viralityScore`, `advocacyScore`, …) that compose the key metrics. |
| `model`              | The formulas, constants, and derived intermediate values used — fully transparent scoring. |
| `interpretation`     | Plain-English narrative summary of the metrics.                                      |

**Sample response (200) (abridged):**

```json
{
  "generatedAt": "2026-06-06T18:30:11 IST",
  "celebrity": {
    "entityId": "33", "name": "Sai Dharam Tej", "type": "CELEBRITY",
    "trackedKeywords": ["SaiDharamTej", "saidharamtej"],
    "activePlatforms": ["x", "youtube"], "totalPosts": 940, "fanBaseSize": 1820,
    "observationWindow": { "firstSeen": "2025-01-11T09:02:00 IST", "lastSeen": "2026-05-30T21:44:00 IST",
      "observationSpanDays": 504.5, "averagePostsPerDay": 1.9 }
  },
  "headlineMetrics": {
    "predictedBrandValueUsd": 1284000, "predictedBrandValueDisplay": "$1.3M",
    "socialMediaReachValue": 982121, "fanEngagementValue": 213450, "endorsementScore": 71.4
  },
  "keyMetricsPercent": {
    "socialMediaInfluence": { "score": 64.2, "band": "High" },
    "brandPower":           { "score": 58.9, "band": "Moderate" },
    "fanLoyalty":           { "score": 72.1, "band": "High" },
    "controversyRisk":      { "score": 18.3, "band": "Low" }
  },
  "reachBreakdown":      { "total": 982121, "x": 704000, "youtube": 278121 },
  "engagementBreakdown": { "total": 213450, "x": 160000, "youtube": 53450 },
  "sentiment": { "positive": 612, "negative": 84, "neutral": 244,
    "netSentiment": 0.56, "label": "Predominantly Positive", "volatility": 0.214, "negativeBurstShare": 0.05 },
  "topAdvocates":  [ { "global_user_id": "@fanpage1", "hawkes_alpha": 0.91, "post_count": 22 } ],
  "scoreDrivers":  { "reachScore": 0.71, "viralityScore": 0.64, "advocacyScore": 0.58, "...": "..." },
  "model":         { "description": "Bounded, monotonic scoring model. ...", "formulas": { "...": "..." } },
  "interpretation": [
    "Sai Dharam Tej has a predicted brand value of $1.3M, driven by a high social-media reach value of 982,121 and 213,450 fan interactions.",
    "Controversy Risk is low at 18% — a safe association for brand partners."
  ]
}
```

---

### 5g. Narrative Novelty Scoring API

Corpus-relative "High-Concept Narrative Novelty" scorer (`NarrativeNoveltyService`). A movie's
novelty is its embedding cosine distance to its nearest neighbours among historical synopses in the
same primary genre (so genre alone can't drive the score), rank-normalised against a leave-one-out
reference distribution built from `movies_data_collection`, then rescaled into the fixed
`[0.30, 0.45]` impact band assigned to this factor. Embeddings come from a local Ollama model —
dense semantic vectors, so paraphrased synopses about a similar premise land close together rather
than looking artificially "novel" for sharing no vocabulary. Sequels/remakes are detected by title
pattern and penalised (`FRANCHISE_PENALTY = 0.7`) regardless of text distance.

**`POST /api/marketing/narrative-novelty/score`**

Scores any synopsis, including upcoming/unreleased titles not yet in the database.

Request body:

```json
{ "movieName": "Untitled Thriller Project", "genre": "Thriller", "synopsis": "A detective races to..." }
```

`movieName` and `genre` are optional (`movieName` defaults to `"Untitled"`); `synopsis` is required
— `400` with `{"error": "synopsis is required"}` if missing/blank.

**`GET /api/marketing/narrative-novelty/lookup?movieName=<name>`**

Scores a title already present in `movies_data_collection`, using its stored genre/synopsis.
Returns `404` with `{"message": "No synopsis found for this movie_name"}` if no matching row has a
synopsis.

Both endpoints return the same shape:

```json
{
  "movieName": "Untitled Thriller Project",
  "primaryGenre": "thriller",
  "genreFallback": false,
  "genreGroupSize": 214,
  "neighborsUsed": 10,
  "franchiseDetected": false,
  "rawNovelty": 0.412,
  "percentile": 0.71,
  "score": 0.407,
  "nearestNeighbors": [
    { "movieName": "The Silent Ledger", "similarity": 0.612 },
    { "movieName": "Cold Case Protocol", "similarity": 0.588 }
  ]
}
```

`genreFallback: true` means fewer than 5 corpus titles shared the primary genre, so neighbours were
drawn from the whole corpus instead. `score` is the final `[0.30, 0.45]`-banded value; `rawNovelty`
and `percentile` are the intermediate corpus-relative figures.

The underlying embedding corpus is rebuilt (and `narrative_novelty_score_v2`/`_raw_v2` persisted for
every title) by `POST /api/admin/recompute-narrative-novelty` — see [Enrichment Admin
API](#9-enrichment-admin-api).

---

### 6. Viral Seeds & Aspect Drivers

**`GET /api/marketing/viral-seeds?keyword=<kw>`**

Top **50** seeding candidates for a keyword, ranked by `influence_rank` (Hawkes α). Matches
both the `top_genres` aspect labels and the literal `keyword` column on every source table.

Query params:

| Name      | Required | Description                                          |
|-----------|----------|------------------------------------------------------|
| `keyword` | yes      | Term/title to search. Wildcards added server-side.   |

```json
[
  {
    "rank": 1,
    "author": "u_182374",
    "hawkesAlpha": 0.92,
    "moiScore": 18.4,
    "tribe": "Cinephile-Critic",
    "primaryPlatform": "x",
    "outreachHandle": {
      "platform": "x",
      "profile_url": "https://x.com/janedoe",
      "permalink":   "https://x.com/janedoe/status/12345"
    },
    "reachSignals": {
      "x_views_count":        982121,
      "instagram_like_count":      0,
      "reddit_score":          12034,
      "youtube_comment_count":     0
    }
  }
]
```

**`GET /api/marketing/aspect-drivers/{keyword}`**

Aspect-level sentiment across all four platforms, split into **Strengths** (avg sentiment > 0)
and **Weaknesses** (avg sentiment < 0). Aspect nouns are extracted with Stanford CoreNLP.
An aspect must be mentioned in at least 3 posts to appear.

Sentiment sourcing:

| Platform           | Score sourcing                                                           |
|--------------------|--------------------------------------------------------------------------|
| `x_posts`          | Continuous `sentiment_score` column (range `[-1, 1]`).                   |
| Others (`youtube_comments`, `reddit_posts`, `instagram_posts`) | `sentiment_category` mapped: `positive → +0.6`, `negative → -0.6`, else `0.0`. |

```json
{
  "keyword": "Avengers",
  "totalPostsAnalyzed": { "x": 412, "youtube": 318, "reddit": 211, "instagram": 99, "total": 1040 },
  "strengths":  [ { "aspect": "cast",      "averageSentiment":  0.51, "postsMentioning": 184, "impactScore":  0.487 } ],
  "weaknesses": [ { "aspect": "pacing",    "averageSentiment": -0.42, "postsMentioning": 132, "impactScore": -0.401 } ],
  "byPlatform": {
    "x":         { "strengths": [...], "weaknesses": [...] },
    "youtube":   { "strengths": [...], "weaknesses": [...] },
    "reddit":    { "strengths": [...], "weaknesses": [...] },
    "instagram": { "strengths": [...], "weaknesses": [...] }
  }
}
```

`impactScore` shrinks toward 0 for low-volume aspects (formula: `avg * n / (n + 3)`),
so a 4-post outlier can't outrank a 200-post consensus.

**`GET /api/marketing/aspect-drivers?entityId={id}`**

Entity-scoped variant. Instead of a single keyword, it resolves the entity's tracked
keyword set (`managed_entities` → `entity_keywords`) and aggregates aspect drivers across
**all** of them — so you can ask "how is entity 29 perceived?" without first knowing which
keywords it tracks. Keywords are matched exactly (case-insensitive), the same scoping the
F11 [entity report](#5c-entity-report-api) uses.

The payload is identical to the keyword variant, with the entity identity prepended in place
of `keyword`:

```json
{
  "entityId": "29",
  "name": "Madhavan",
  "type": "CELEBRITY",
  "trackedKeywords": ["Maddy", "Madhavan", "RMadhavan", "maddy", "madhavan", "rmadhavan"],
  "totalPostsAnalyzed": { "x": 412, "youtube": 318, "reddit": 211, "instagram": 99, "total": 1040 },
  "strengths":  [ ... ],
  "weaknesses": [ ... ],
  "byPlatform": { ... }
}
```

Returns `404` if no entity has that id. An entity that exists but has no matching precomputed
posts returns zero counts with empty strengths/weaknesses.

---

### 7. Top Spreaders

**`GET /api/marketing/top-50-spreaders/{keyword}[?platform=<platform>]`**

Top 50 authors for posts matching `{keyword}` in the last 90 days, ranked by
**Viral Potential Score**:

```
VPS = (likes + 3 × comments) × (1 + α) × reach_multiplier
```

Engagement count rewards authors whose audience actively reacts (not just passive viewers).
The `(1 + α)` factor lets Hawkes infectivity boost bursty cascade-starters without zeroing
out high-engagement organic spreaders whose cadence fits α ≈ 0. Comments are weighted 3×
likes (more user effort, stronger sharing signal).

`reach_multiplier` folds in raw audience size (`total_views`) on a log scale —
`1 + log10(1 + views) / 10` — so that among authors with comparable engagement, the one
actually reaching more people ranks higher (e.g. ~300k views is a ~1.55× boost, not a
300000× one). It's `1.0` (neutral) for YouTube/Reddit/Instagram authors, since those
platforms don't track views and shouldn't be penalized for a signal that was never collected.

Authors need at least `top-spreaders.min-posts` matching posts (default 1) **and** a
strictly positive VPS to be ranked — an author with a single comment and no recorded
likes/replies (common on `youtube_comments`, where those columns are frequently null) scores
exactly 0 and is dropped rather than filling out the list when a keyword's qualifying pool is
thin. Ties break on `total_views` then author name, so results are deterministic.

By default the ranking is computed across all four tracked platforms combined — **X**,
**YouTube**, **Reddit**, and **Instagram**. Pass `platform` (case-insensitive) to restrict
the ranking to a single platform instead:

| `platform` value | Source table        |
|-------------------|----------------------|
| `x`               | `x_posts`            |
| `youtube`         | `youtube_comments`   |
| `reddit`          | `reddit_posts`       |
| `instagram`       | `instagram_posts`    |

An unrecognized `platform` value returns `400 Bad Request` with a body listing the valid
options, e.g.:

```
GET /api/marketing/top-50-spreaders/Coolie?platform=tiktok
→ 400 "Unknown platform 'tiktok'. Must be one of: x, youtube, reddit, instagram"
```

```json
[
  {
    "author": "janedoe",
    "viral_potential_score": 1061.0,
    "alpha": 0.0,
    "reach_multiplier": 1.398,
    "engagement_count": 759.0,
    "total_likes": 666,
    "total_comments": 31,
    "total_views": 9518,
    "engagement_rate": 0.0797,
    "average_sentiment_score": 0.41
  }
]
```

Use `average_sentiment_score` to avoid seeding with high-influence detractors.
`engagement_rate` (engagement / views) is a useful secondary filter for picking authors
whose audience converts impressions into reactions.

---

### 8. Lookalike Discovery

**`POST /api/marketing/find-lookalikes`**

Returns up to 100 lookalike users for a seed author, computed by `LookalikeDiscoveryService`.

Request body:

```json
{ "seedAuthorId": "u_182374" }
```

Response:

```json
[ { "global_user_id": "u_553120", "similarity": 0.871, "...": "..." } ]
```

Errors:
- `400 Bad Request` with body `"seedAuthorId is required"` if missing/blank.
- `400 Bad Request` with the exception message if the discovery service rejects the input.

**`GET /api/marketing/find-lookalikes/diff?seedAuthorId=<id>&limit=<n>`**

Diagnostic comparison harness (not for production consumption): runs both the legacy L2-distance
ranking (`findLookalikesL2Legacy`) and the current production block-wise method
(`findLookalikes`) for the same seed and returns them side by side, plus rank movement for
candidates present in both lists. `limit` defaults to `25`. Used for ongoing similarity-weight
tuning — the legacy method's scores concentrate near `0.013` in this ~600-dim space and aren't
meaningfully displayable on their own.

```json
{
  "seedAuthorId": "u_182374",
  "limit": 25,
  "overlap_count": 14,
  "overlap_fraction": 0.56,
  "current_score_range": { "min": 0.011, "max": 0.017 },
  "prototype_score_range": { "min": 0.42, "max": 0.93 },
  "rank_movement_shared": [
    { "global_user_id": "u_553120", "rank_current": 3, "rank_prototype": 1, "rank_delta": 2 }
  ],
  "current_top": [ { "global_user_id": "u_553120", "...": "..." } ],
  "prototype_top": [ { "global_user_id": "u_553120", "similarity": 0.93 } ]
}
```

Same `400` error contract as `/find-lookalikes` for a missing/blank `seedAuthorId`.

---

### 9. Enrichment Admin API

All endpoints under `/api/admin` are synchronous, **long-running** recompute triggers — call them
from a job runner or admin tool, not request-path code. All are `POST` with no request body.

| Endpoint                                       | Recomputes                                                                    |
|-------------------------------------------------|--------------------------------------------------------------------------------|
| `POST /api/admin/run-enrichment`                 | `MarketingEnrichmentEngine.enrichAndSave()` — the entire `marketing_target_profiles` table (Hawkes α, MOI, tribes, genres). |
| `POST /api/admin/run-engagement-rating`          | `UserEngagementRatingService` — corpus-relative `engagement_score_raw`/`engagement_rating` on `marketing_target_profiles`. |
| `POST /api/admin/run-graph-population`           | `GraphPopulationService` — rebuilds `graph_nodes`/`graph_edges` (MOVIE/USER nodes, POSTED_ABOUT/RETWEETED edges) backing the [User Graph API](#12-user-graph-api). |
| `POST /api/admin/resolve-identities`             | `CrossPlatformIdentityResolver` — (re)populates `user_identity_link` from every distinct author across `x_posts`/`youtube_comments`/`reddit_posts`/`instagram_posts`. |
| `POST /api/admin/recompute-narrative-novelty`    | `NarrativeNoveltyService` — rebuilds the synopsis embedding corpus and persists `narrative_novelty_score_v2`/`_raw_v2`/`_franchise_flag` on `movies_data_collection`. Backs the [Narrative Novelty API](#5g-narrative-novelty-scoring-api). |
| `POST /api/admin/recompute-narrative-novelty-v1` | Same algorithm as above, but persists into the legacy `narrative_novelty_score` column instead (no `_v2` suffix). |
| `POST /api/admin/recompute-conflict-balance`     | `ConflictBalanceService` — corpus-relative `conflict_balance_score` on `movies_data_collection`, from Stanford CoreNLP per-sentence sentiment balance of each synopsis. |

**`POST /api/admin/run-enrichment`**

Synchronously triggers `MarketingEnrichmentEngine.enrichAndSave()`, which recomputes
the entire `marketing_target_profiles` table. **Long-running** — call from a job runner
or admin tool, not from request-path code.

Response: `200 OK` with body `"done"`.

The other six endpoints return `200 OK` with a JSON summary object (row counts and, where
applicable, corpus-wide validation stats such as correlation against `revenue`/`imdb_rating`);
`resolve-identities` returns `200 OK` with a plain-text `"inserted=<n>"` body.

---

### 10. Diagnostic / Test Endpoints

These endpoints are for verification of the math/mapping pipeline. They expose internals
and should be put behind an internal route in production.

| Endpoint                                  | Purpose                                                                 |
|-------------------------------------------|-------------------------------------------------------------------------|
| `GET /test/process-user/{author}`         | End-to-end check: raw per-platform counts + Hawkes (`mu`, `alpha`) + MOI broken down per platform. |
| `GET /api/test/temporal-audit/{author}`   | Full per-post Hawkes audit: timeline, per-post excitation spikes, cluster membership, branching ratio. |
| `GET /api/test/raw-mapping/{author}`      | Compares raw DB column values against what `PostMapper` actually reads, for catching schema/mapper drift. |

---

### 11. Ask Engine (experimental)

A natural-language → database + mathematician engine lives under `com.lit.fire.flame.nlq`. Given a
question in plain English and a per-request target database connection, it introspects the schema,
drafts SQL with a pluggable LLM (Claude first), validates and executes it **read-only**, and
composes an answer. Target connections are fully isolated from AuraMath's own datasource, and
requests may skip tables/columns. It can also answer across **several** registered databases at once
(F12), caches their schemas hourly (F13), and falls back to the LLM for formulas not yet in code (F14) —
all below. See [`docs/ask-engine/DESIGN.md`](docs/ask-engine/DESIGN.md) for the pipeline, guarantees,
and the F0–F14 roadmap.

The connection layer (F1) is in place. Supported target drivers: PostgreSQL (`jdbc:postgresql:`),
SQLite (`jdbc:sqlite:`), and MySQL (`jdbc:mysql:`); every connection is opened **read-only** and is
fully isolated from AuraMath's own datasource.

Schema introspection (F2) is in place too: the engine reads structure only (tables, columns, types,
keys — never row data), excludes system schemas, and renders a compact schema for the model. A
request may carry `skipTables` and `skipColumns` lists (unioned with the server-side
`aura.ask.default-skip-tables`); matching is case-insensitive and schema-qualified-aware
(`users` or `public.users`; `table.column` or `schema.table.column`). **Skipped tables and columns
are invisible to the model** — they are removed from the introspected schema before it is rendered
and are re-enforced again at validation/execution.

The LLM layer (F3) is in place: a provider-neutral `nlq.llm.LlmClient` (request/response/typed
exception) with a first `ClaudeLlmClient` that calls the Anthropic Messages API directly over
`java.net.http.HttpClient` + Gson (no SDK). The API key comes from `secrets.txt` (`anthropic.api.key`,
see [Configuration](#configuration)), the default model is `claude-opus-4-8`, structured output is
requested with a JSON Schema (returned as a parsed JSON object), and the provider is chosen by
`aura.ask.llm-provider`. To add another provider, implement `LlmClient` and add a branch in
`AskEngineConfiguration` — callers are untouched.

NL → SQL generation (F4) is in place: `nlq.sql.SqlGenerationService` drafts a single read-only
`SELECT`/`WITH` query from the question and the (skip-list-filtered) schema, using the detected SQL
dialect for dialect-correct syntax and always bounding the result with a `LIMIT` ≤ `aura.ask.max-rows`.
**The engine asks for clarification rather than guessing** when a question can't be answered from the
non-skipped schema — it returns a specific question instead of inventing SQL, and it also re-asks if
the drafted query references a table that isn't in the provided schema. The drafted SQL is still not
trusted at this point.

The SQL safety guard (F5) is in place: `nlq.sql.SqlSafetyGuard.validate(...)` is the **trust
boundary** between the model and the database — the LLM is untrusted, and no query reaches execution
without passing it. It is deterministic and **fails closed**, returning a normalized, row-capped SQL
string or throwing a typed `UnsafeSqlException`. For integrators, the read-only guarantees it
enforces are:

- **Single read-only statement.** Exactly one statement, beginning with `SELECT`/`WITH`; any
  interior `;` (statement chaining) is rejected, as is any DML/DDL/DCL keyword
  (`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`CREATE`/`ALTER`/`DROP`/`TRUNCATE`/`GRANT`/`REVOKE`/`CALL`/
  `EXEC`/`COPY`/`ATTACH`/`PRAGMA`/`VACUUM`/write-form `INTO`, …).
- **No smuggling.** SQL comments (`--`, `/* */`) are refused outright, and the query is parsed by a
  real SQL parser (JSqlParser) rather than trusted by pattern-matching alone.
- **No data exfiltration.** Dialect-specific file/network helpers are blocked
  (`pg_read_file`, `lo_import`/`lo_export`, `dblink`, MySQL `LOAD_FILE`, `INTO OUTFILE/DUMPFILE`,
  SQLite `ATTACH`).
- **Skip-list re-enforced.** Every referenced table must exist in the (already skip-filtered) schema
  and must not be skip-listed; skipped columns are rejected too — so skipped objects can neither be
  seen nor touched.
- **Bounded results.** A missing `LIMIT` is injected and an over-large one is lowered to
  `aura.ask.max-rows` (a smaller explicit limit is preserved).

Bounded execution (F6) runs only what this guard returns.

The mathematician layer (F7) is in place: `nlq.math.AnswerSynthesisService` turns the retrieved rows
into a final natural-language answer and **applies formulas deterministically**. It is
plan-then-evaluate: the LLM proposes *which* formula(s) apply over *which* columns (mean, weighted
average, growth rate, std dev, regression slope, percentile, CAGR, correlation, or an ad-hoc
expression) — **never the final numbers** — and Java computes every value with `commons-math3` and a
restricted `exp4j` evaluator. The model then writes the prose answer *given* those computed values and
is forbidden from inventing any figure beyond them. The returned `AskAnswer` therefore includes the
**formulas applied** (each with its inputs and Java-computed result) and the **exact computed values**
keyed by name, alongside the answer text, the SQL, the assumptions, and a row preview. Bad data never
fabricates a number: an empty result set short-circuits to a factual *"no data"* answer (with no model
call), a pure-lookup question is answered straight from the rows, and division-by-zero / non-numeric /
empty columns skip the affected formula with a recorded note. So for *"what is the average order
value"*, the engine plans a `mean` over the order-value column, `commons-math3` computes it, and the
answer states that exact figure.

The orchestrator and public endpoint (F8) are in place: `nlq.api.AskOrchestrator` wires F1–F7 into one
end-to-end call exposed at **`POST /api/ask`**. It opens the per-request target connection (F1),
introspects it through the effective skip-list (F2), drafts SQL (F4), and — unless a clarification is
needed — validates (F5), executes (F6), and synthesizes the answer (F7), **always closing the
connection** in a `finally` block. The effective skip-list is the union of `aura.ask.default-skip-tables`,
the connection's skips, and the request's, honoured at every layer; an optional per-request `maxRows`
is clamped to `aura.ask.max-rows` (it can only lower the cap). The response carries the validated SQL
that actually ran, a rows preview, the formulas applied with their computed values, the assumptions,
and per-stage timings. A question that can't be answered from the non-skipped schema (e.g. one
targeting a skipped table) returns a **clarification**, never a leak. Errors are mapped to clean,
sanitized HTTP statuses (no credentials, driver text, or prompts ever leak).

Sensitive-data hardening (F9) layers three controls over the basic table-skipping, all enforced at
every stage (schema, SQL validation, execution, and output):

- **Skipped tables/columns** — invisible: removed from the schema and rejected at validation. The
  per-request `skipTables`/`skipColumns` are unioned with the server-side `aura.ask.default-skip-tables`
  and `aura.ask.default-skip-columns`.
- **Masked columns** (`aura.ask.masked-columns`) — may be **aggregated** (e.g. `count(email)`) but
  their **raw values are never returned**: a raw projection (or a `SELECT *` over a table that owns
  one) is rejected, and any masked value that still reaches a result is **partial-masked** before the
  response (and before the mathematician sees it).
- **Auto-skip name patterns** (`aura.ask.auto-skip.*`) — columns/tables whose name matches a
  configurable, case-insensitive regex (defaults cover `password`/`secret`/`ssn`/`token`/`api_key`/
  `private_key`/`card_number`) are auto-skipped **even with no explicit request** — so a
  `password_hash` column never appears in the schema, SQL, or output. On by default; disable with
  `aura.ask.auto-skip.enabled=false`. The introspector logs (DEBUG, names only — never values) which
  objects it skipped or masked so operators can verify.

See [Operational Notes](#operational-notes) for the config keys and
[`docs/ask-engine/DESIGN.md`](docs/ask-engine/DESIGN.md) for the full skip/mask/redact model and
precedence.

Audit logging & observability (F10) make every Ask traceable without leaking anything. The
orchestrator emits exactly one **credential-free** structured (JSON) audit line per request — answered,
clarification, or error — carrying a `requestId`, the target DB host/product (host only, never the URL,
username, or password), the question, the generated SQL, tables used, row count/truncation, per-stage
latency, LLM token usage, the sanitized outcome/reason, and which objects were skipped/masked. **No
password, API key, or masked row value is ever logged.** The same `requestId` is echoed back on every
response (the answer **and** error bodies), so an operator can correlate what the caller saw with the
log. Auditing is **log-only by default**; set `aura.ask.audit.persist=true` to also persist each record
to a table in AuraMath's **own** database (via the app `JdbcTemplate`, never the target connection — the
table is not auto-created; DDL in [Operational Notes](#operational-notes)). Lightweight in-memory
counters (requests, answers, clarifications, errors, unsafe-SQL rejections, execution timeouts, LLM
failures) are exposed at `GET /api/ask/admin/metrics` — no Actuator dependency.

Multi-database registry & cross-database answering (F12) let AuraMath answer one question across
**several** databases. Instead of carrying credentials in every request, the host machine running
AuraMath holds a `~/config.secrets` file (path = `aura.ask.secrets-path`); at startup
`nlq.connection.DatasourceRegistry` loads each `ask.db.<name>.*` group into a named, read-only target
(see [`scripts/config.secrets.example`](scripts/config.secrets.example)). An Ask request is then
**credential-free** — it sends only a `question`, optionally a `databases` subset (omit for *all*
registered databases). When more than one database is resolved, the orchestrator opens and introspects
each, asks the model for **one read-only sub-query per database** (separate JDBC connections cannot be
JOINed), validates/executes/redacts each against its own database, and the mathematician layer (F7)
**collates the labeled result sets into a single answer** — computing per-database figures
deterministically in Java. The response then carries a `subQueries` array (the per-database SQL, tables,
and row counts) instead of a single `sql`. Backwards compatible: a request that still supplies its own
`connection` (or a `connections` list) bypasses the registry, and a single resolved database uses the
original single-DB pipeline unchanged. Fan-out is capped by `aura.ask.max-databases` (default 5).

> **Security trade-off.** By design (opt-in), the registry holds target credentials in the server's
> memory — a deliberate relaxation of the "credentials only ever per-request" stance, so questions can
> be asked without shipping credentials each time. Connections are still opened **read-only**, the file
> should be `chmod 600`, and credentials are never logged or returned (`GET /api/ask/databases` exposes
> only name, driver, and host).

Hourly schema cache (F13) avoids introspecting every target on every question. A scheduled job
(`nlq.schema.SchemaRefreshJob`, default hourly via `aura.ask.schema-cache.refresh-interval-ms`)
introspects each registered database and stores its full structured schema as JSON in a table
(`ask_schema_cache`, auto-created) in AuraMath's **own** Postgres; the Ask path then answers from the
cache (`nlq.schema.SchemaCacheService`), filtering it for any per-request skips. It is fail-soft: a
cache miss or any cache error falls back to live introspection, so the engine still works before the
first refresh. Execution connections are opened **lazily** — only for the databases a question actually
queries.

LLM-computed math fallback + formula-gap logging (F14). The mathematician layer (F7) computes catalog
formulas deterministically in Java. When a question needs a formula **not** in the catalog, instead of
skipping it the engine asks the LLM to compute that one value from the retrieved rows
(`nlq.math.LlmComputeService`) and uses it in the answer — the only place the model does arithmetic, and
only for uncatalogued formulas. Every such use is recorded by `nlq.audit.FormulaGapLogger` (a structured
log line and, by default, a row in `ask_formula_gap` in AuraMath's own DB) so the formula can be
implemented in code in a later release. Records carry no credentials and no raw row values beyond the
one computed figure.

Missing-data clarifications (req #6). When a question can't be answered, the response's
`clarificationQuestion` is now accompanied by a `missingData` list naming the specific data the
schema(s) lack (e.g. "a refunds table or a refund-date column"), so callers learn exactly what is
missing rather than only being re-asked.

| Endpoint                          | Purpose                                                              |
|-----------------------------------|----------------------------------------------------------------------|
| `GET /api/ask/databases`          | List the registry's databases available to target (name/driver/host; no creds). (F12) |
| `POST /api/ask/test-connection`   | Open a read-only connection to a target DB and probe it (`SELECT 1`).|
| `POST /api/ask`                   | Answer a question against the registry (or an explicit target/targets), read-only; collates across databases. |
| `GET /api/ask/admin/metrics`      | Operational counters for the Ask engine (counts only). (F10)        |

**`POST /api/ask/test-connection`** — attempts an isolated, read-only connection to the supplied
target database and runs a trivial probe. The `driver` field is optional (auto-detected from the URL
scheme). The password is never echoed back in the response or logs.

Request:

```json
{
  "jdbcUrl": "jdbc:postgresql://localhost:5432/analytics",
  "username": "readonly_user",
  "password": "***",
  "driver": "postgresql"
}
```

Response (success):

```json
{
  "connected": true,
  "databaseProductName": "PostgreSQL",
  "databaseProductVersion": "14.10",
  "error": null
}
```

Response (rejected URL — e.g. `jdbc:h2:mem:test`, returns `400`):

```json
{
  "connected": false,
  "databaseProductName": null,
  "databaseProductVersion": null,
  "error": "Unsupported JDBC URL scheme. Allowed: jdbc:postgresql:, jdbc:sqlite:, jdbc:mysql:"
}
```

**`POST /api/ask`** — answer a natural-language `question` against one or more target databases, fully
read-only. Runs the whole F1–F7 pipeline and returns the validated SQL that ran, a rows preview, the
formulas applied, and a natural-language answer. Returns a **clarification** (HTTP `400`) when the
question can't be answered from the non-skipped schema. Any `password` is used only to open the
connection and never appears in the response or logs.

**Targets are resolved in precedence order:** an explicit `connections` list (federated) → a single
`connection` → the server-side registry. The registry path (recommended) carries **no credentials** —
just a `question` and optionally a `databases` subset (omit for all registered databases):

```json
{ "question": "Compare total order revenue against the total amount we billed.",
  "databases": ["orders", "billing"] }
```

When more than one database is resolved, the answer collates across them and the response carries a
`subQueries` array (per-database SQL/tables/rows) and a `null` top-level `sql`; `tablesUsed` entries are
prefixed `database.table` and `rowCount` is the total across databases. Example federated response body:

```json
{
  "requestId": "f0e1d2c3-...",
  "answer": "Order revenue was $1,284,000 across the orders DB; billed total was $1,190,500 in billing — a $93,500 gap.",
  "sql": null,
  "subQueries": [
    { "database": "orders",  "sql": "SELECT amount FROM orders LIMIT 1000",  "tablesUsed": ["orders"],   "rowCount": 842, "truncated": false },
    { "database": "billing", "sql": "SELECT total FROM invoices LIMIT 1000", "tablesUsed": ["invoices"], "rowCount": 611, "truncated": false }
  ],
  "tablesUsed": ["orders.orders", "billing.invoices"],
  "computedValues": { "orders_revenue": 1284000.0, "billed_total": 1190500.0, "gap": 93500.0 },
  "rowCount": 1453,
  "truncated": false
}
```

For an ad-hoc single database, supply a `connection` object (the same `ConnectionRequest` as
`test-connection`; `skipTables`, `skipColumns`, `model`, and `maxRows` are optional):

```json
{
  "connection": {
    "jdbcUrl": "jdbc:postgresql://localhost:5432/analytics",
    "username": "readonly_user",
    "password": "***",
    "driver": "postgresql"
  },
  "question": "What is the average order value for orders placed last month?",
  "skipTables": ["audit_log", "public.users_pii"],
  "skipColumns": ["orders.internal_notes"],
  "model": "claude-opus-4-8",
  "maxRows": 500
}
```

Response (success, `200`):

```json
{
  "requestId": "a1b2c3d4-5e6f-7890-abcd-ef0123456789",
  "clarificationNeeded": false,
  "clarificationQuestion": null,
  "answer": "The average order value for orders placed last month was $42.17 across 1,284 orders.",
  "sql": "SELECT avg(amount) AS avg_order_value FROM orders WHERE created_at >= date_trunc('month', now() - interval '1 month') AND created_at < date_trunc('month', now()) LIMIT 500",
  "tablesUsed": ["orders"],
  "formulasApplied": [
    {
      "name": "average_order_value",
      "expression": "mean(avg_order_value)",
      "inputs": { "column": "avg_order_value", "count": 1 },
      "result": 42.17
    }
  ],
  "computedValues": { "average_order_value": 42.17 },
  "assumptions": ["'order value' maps to orders.amount"],
  "rowsPreview": [ { "avg_order_value": 42.17 } ],
  "rowCount": 1,
  "truncated": false,
  "timingMillis": {
    "connectMillis": 31,
    "introspectMillis": 88,
    "generateMillis": 940,
    "validateMillis": 3,
    "executeMillis": 12,
    "synthesizeMillis": 1120,
    "totalMillis": 2194
  }
}
```

Response (clarification — e.g. the question targets a skipped table, returns `400`):

```json
{
  "requestId": "b2c3d4e5-6f70-8901-bcde-f01234567890",
  "clarificationNeeded": true,
  "clarificationQuestion": "Which table holds the data you mean? The question could not be answered from the available schema.",
  "answer": null,
  "sql": null,
  "tablesUsed": [],
  "formulasApplied": [],
  "computedValues": {},
  "assumptions": [],
  "rowsPreview": [],
  "rowCount": 0,
  "truncated": false,
  "timingMillis": { "connectMillis": 29, "introspectMillis": 84, "generateMillis": 610, "totalMillis": 723 }
}
```

HTTP status codes:

| Status | When | Body |
|--------|------|------|
| `200` | A question was answered. | `AskResponse` (answer) |
| `400` | Clarification needed (question can't be answered from the non-skipped schema). | `AskResponse` (clarification) |
| `400` | Malformed request (missing `connection`/`question`) or rejected connection details (bad scheme/driver/denylisted parameter). | `{ "error": "…" }` |
| `422` | The drafted query is unsafe or can't be made a single bounded read-only statement. | `{ "error": "…" }` |
| `502` | The LLM call or the target connection/execution failed. | `{ "error": "…" }` |
| `504` | The LLM call or the query timed out. | `{ "error": "…" }` |
| `503` | The engine is disabled (`aura.ask.enabled=false`). | `{ "error": "the Ask engine is disabled" }` |

All error bodies are **sanitized** — never the password, raw driver text, prompt, or a stack trace —
and carry the same `requestId` as the audit log (`{ "error": "…", "requestId": "…" }`) so a failure can
be correlated server-side.

---

## Common Models

**Influence Tier** (branching ratio = α / β, where β = 1.0 minutes⁻¹):

```
β = 1.0 (decay rate, fixed)
half-life = ln(2) / β ≈ 0.69 minutes
branching ratio = α / β  ∈ [0, ~1]
Viral Node    ≥ 0.8
Amplifier     0.6 – 0.8
Participant   0.3 – 0.6
Observer      < 0.3
```

**Posting Style**:

| Style                | Trigger                                            |
|----------------------|----------------------------------------------------|
| `Steady Poster`      | No bursts detected.                                |
| `Reactive Poster`    | 1 burst.                                           |
| `Burst Poster`       | ≥ 2 distinct bursts, none with ≥10 posts.          |
| `Power Burst Poster` | Any burst with ≥ 10 posts.                         |

A "burst" requires ≥ `HawkesAuditService.CLUSTER_MIN` posts within `CLUSTER_WIN` minutes of a keyword event.

**Audience Classification** (combines `dominant_tone` × `branching_ratio`):

| Tone × BR              | Label                          |
|------------------------|--------------------------------|
| negative + BR ≥ 0.7    | `Critical Power Influencer`    |
| negative + BR < 0.7    | `Active Critic`                |
| positive + BR ≥ 0.7    | `Movie Buff`                   |
| positive + BR < 0.7    | `Positive Engager`             |
| neutral / fallback     | `Neutral Informer`             |

---

## Integration Recipes

### Pull viral seeds for a movie launch

```bash
curl 'http://localhost:8081/api/marketing/viral-seeds?keyword=Avengers' | jq
```

Pair with `/api/marketing/aspect-drivers/Avengers` to learn what aspects to lead the
copy with. Cross-check each candidate's `average_sentiment_score` from
`/api/marketing/top-50-spreaders/Avengers` before seeding.

### Build a per-genre channel-buy report

```bash
curl 'http://localhost:8081/api/marketing/genre/horror/channel-strategy' | jq
curl 'http://localhost:8081/api/marketing/genre/horror/super-spreaders'  | jq
curl 'http://localhost:8081/api/marketing/genre/horror/potential-viewers' | jq
```

### Generate an analyst-ready brief for a known influencer

```bash
curl 'http://localhost:8081/api/marketing/user-report/janedoe' | jq
```

The call also writes `janedoe` into `author_categories` for later filtering through
`/api/marketing/users`.

### Generate a shareable intelligence report for an entity

```bash
# discover an entity id and its keywords
curl 'http://localhost:8081/api/marketing/celebrity' | jq
# full report aggregated across all of the entity's keywords (id 21 = Madhavan)
curl 'http://localhost:8081/api/marketing/entity-report/21' | jq
```

`/api/marketing/entity/21/report` returns the identical payload — use the `entity-report`
URL for a prospect-facing share and the `entity/{id}/report` URL for the in-app view.

### Find lookalikes given a known seed

```bash
curl -X POST 'http://localhost:8081/api/marketing/find-lookalikes' \
     -H 'Content-Type: application/json' \
     -d '{"seedAuthorId": "u_182374"}'
```

### Refresh enrichment tables after a fresh data import

```bash
curl -X POST 'http://localhost:8081/api/admin/run-enrichment'
curl -X POST 'http://localhost:8081/api/marketing/users/sync'
```

### Ask a question of an external database

Point the Ask engine at any read-only-reachable Postgres/SQLite/MySQL database and ask in plain
English. Connection details are per-request and fully isolated from AuraMath's own datasource; list
any sensitive tables/columns in `skipTables`/`skipColumns` so they are invisible to the model and
rejected at validation.

```bash
curl -X POST 'http://localhost:8081/api/ask' \
     -H 'Content-Type: application/json' \
     -d '{
           "connection": {
             "jdbcUrl": "jdbc:postgresql://localhost:5432/analytics",
             "username": "readonly_user",
             "password": "***",
             "driver": "postgresql"
           },
           "question": "What is the average order value for orders placed last month?",
           "skipTables": ["audit_log", "users_pii"],
           "maxRows": 500
         }' | jq
```

A successful call returns the validated `sql` that ran, a `rowsPreview`, the `formulasApplied` with
their computed values, and a natural-language `answer`. If the question can't be answered from the
non-skipped schema (for example it targets a skipped table), the engine responds `400` with
`clarificationNeeded: true` and a `clarificationQuestion` to refine — never a leak. First probe
reachability with `POST /api/ask/test-connection` using the same `connection` block.

---

## Errors

There is no shared error envelope. Spring Boot's default error handler returns:

```json
{
  "timestamp": "2026-05-16T03:51:52.137+00:00",
  "status":  500,
  "error":   "Internal Server Error",
  "message": "<exception message>",
  "path":    "/api/marketing/user-report/janedoe"
}
```

`server.error.include-message=always` and `server.error.include-binding-errors=always` are
enabled, so error messages are propagated to the client. **Disable these in production** if
you don't want exception messages reaching consumers.

Per-endpoint contracts:
- `GET /api/marketing/user-profile/{globalUserId}` → `404` if no `user_identity_link` row.
- `POST /api/marketing/find-lookalikes` → `400` if `seedAuthorId` missing/blank, or if the
  service throws `IllegalArgumentException`.
- All others → infrastructure failures bubble up as `500`.

---

## Operational Notes

- **Stanford CoreNLP startup cost.** `AspectSentimentAnalyzer` is initialised once per
  application lifecycle. First request after boot pays a one-time pipeline warm-up (several
  seconds).
- **Scheduled sync.** `@EnableScheduling` is on. `AuthorCategoryController.scheduledResync()`
  re-runs sync every 24h, starting 5 minutes after boot. Disable the scheduler or unschedule
  the bean if you want manual control.
- **JSONB unwrapping.** `JsonbUtil.asTree` parses `platform_handles`, `top_genres`,
  `peak_activity_times` into proper JSON trees in responses — consumers should expect
  objects, not strings.
- **Hawkes parameter assumption.** β is hard-coded to `1.0` (minute⁻¹). Half-life is
  `ln(2)/β ≈ 0.69 minutes`. If you re-tune β, every interpretation string in
  `MarketingUserReportController` and `TemporalAuditController` is affected.
- **MOI quirks.** `views_count` only exists on `x_posts`. Reddit/Instagram contribute zero
  to MOI by design (no impressions column in schema). YouTube is excluded entirely. See
  the `dataSourceNotes` block in `/test/process-user/{author}` for the canonical mapping.
- **Ask engine sensitive-data controls (F9).** The Ask engine (`/api/ask`) reads its sensitive-data
  policy from `aura.ask` (prefix; `nlq.config.AskEngineProperties`). Defaults are safe and empty for
  the explicit lists, with auto-skip patterns **on**:

  | Key | Default | Meaning |
  |-----|---------|---------|
  | `aura.ask.default-skip-tables` | _(empty)_ | Tables always hidden + rejected, on top of per-request `skipTables`. |
  | `aura.ask.default-skip-columns` | _(empty)_ | Columns always hidden + rejected, on top of per-request `skipColumns`. Entries are `table.column` or `schema.table.column` (case-insensitive). |
  | `aura.ask.masked-columns` | _(empty)_ | Columns aggregatable (e.g. `count`) but never returned raw; matched raw values are partial-masked in the response. Same entry format as skip-columns. |
  | `aura.ask.auto-skip.enabled` | `true` | Master toggle for pattern-based auto-skipping. Set `false` to turn it off. |
  | `aura.ask.auto-skip.patterns` | `.*(password\|passwd\|pwd).*`, `.*secret.*`, `.*(^\|_)ssn(_\|$).*`, `.*token.*`, `.*api[_-]?key.*`, `.*private[_-]?key.*`, `.*(credit[_-]?card\|card[_-]?number).*` | Case-insensitive, full-match name regexes; a matching bare table/column name is auto-skipped. Replace the list to customise. |

  Precedence is a **union** (per-request → server defaults → auto-skip patterns), and **skip beats
  mask** (a column matching both a skip rule and a mask rule is removed entirely). Skipped/masked
  objects are logged at DEBUG by **name only** (never any row value) so operators can audit the policy.
  Example `application.properties`:

  ```properties
  aura.ask.default-skip-tables=audit_log,public.users_pii
  aura.ask.default-skip-columns=orders.internal_notes
  aura.ask.masked-columns=users.email,users.phone
  aura.ask.auto-skip.enabled=true
  # aura.ask.auto-skip.patterns=.*secret.*,.*token.*   # override to customise; comment out to keep defaults
  ```

- **Ask engine audit & observability (F10).** The Ask engine writes one structured (JSON) audit line
  per `/api/ask` request via SLF4J, on logger `com.lit.fire.flame.nlq.audit.AskAuditLogger` at `INFO`.
  Each line is **credential- and row-value-free** and carries the same `requestId` echoed to the client
  (on the answer and on error bodies). Fields: `requestId`, `timestamp`, `outcome`
  (`ANSWERED`/`CLARIFICATION`/`ERROR`), `reason` (sanitized category), `databaseProduct`, `databaseHost`
  (**host[:port] only** — never the URL/username/password), `question`, `generatedSql`, `tablesUsed`,
  `rowCount`, `truncated`, `timingMillis` (per stage), `llm` (`calls`/`inputTokens`/`outputTokens`), and
  `policy` (`skippedTables`/`skippedColumns`/`maskedColumns`, names only). Example:

  ```json
  {"event":"ask.request","requestId":"a1b2…","outcome":"ANSWERED","databaseHost":"db.internal:5432",
   "question":"average order value last month","generatedSql":"SELECT avg(amount) … LIMIT 500",
   "tablesUsed":["orders"],"rowCount":1,"truncated":false,"llm":{"calls":3,"inputTokens":1820,"outputTokens":240},
   "policy":{"skippedTables":["audit_log"],"skippedColumns":[],"maskedColumns":["users.email"]}}
  ```

  | Key | Default | Meaning |
  |-----|---------|---------|
  | `aura.ask.audit.persist` | `false` | When `true`, also persist each record to AuraMath's **own** DB (via the app `JdbcTemplate`, never the target connection). Log-only when `false`. |
  | `aura.ask.audit.table` | `ask_audit_log` | Target table for persisted records. **Not auto-created** — create it yourself. |

  Persistence is **opt-in** and writes to a table you create (the engine never runs DDL against an
  arbitrary database). Suggested DDL (PostgreSQL):

  ```sql
  CREATE TABLE ask_audit_log (
      id                BIGSERIAL PRIMARY KEY,
      request_id        VARCHAR(64)  NOT NULL,
      created_at        TIMESTAMP    NOT NULL,
      outcome           VARCHAR(16)  NOT NULL,
      reason            VARCHAR(256),
      db_product        VARCHAR(128),
      db_host           VARCHAR(256),
      question          TEXT,
      generated_sql     TEXT,
      tables_used       TEXT,
      row_count         INTEGER,
      truncated         BOOLEAN,
      total_millis      BIGINT,
      llm_calls         INTEGER,
      llm_input_tokens  INTEGER,
      llm_output_tokens INTEGER,
      skipped_tables    TEXT,
      skipped_columns   TEXT,
      masked_columns    TEXT
  );
  CREATE INDEX ix_ask_audit_request_id ON ask_audit_log (request_id);
  ```

  **Metrics.** `GET /api/ask/admin/metrics` returns process-wide counters (counts only, monotonic since
  boot): `requests`, `answers`, `clarifications`, `errors`, `unsafeSqlRejections`, `executionTimeouts`,
  `llmFailures`. No Spring Boot Actuator / Micrometer dependency is added; the per-request detail lives
  in the audit log above.

---

### 12. User Graph API

**`GET /api/graph/users?language=<language>&movie=<movieName>`**

Filterable read API over the precomputed `graph_nodes`/`graph_edges` tables (populated by
`GraphPopulationService`, see [Enrichment Admin API](#9-enrichment-admin-api)), returned in a
`{nodes, edges}` shape directly consumable by graph-viz frontends (D3/Cytoscape/vis.js). Unlike the
[Language Marketing API](#5d-language-marketing-api), this endpoint does not re-derive the
mentions → managed_entities join — that join is already baked into the graph tables by the
precompute step, so this is a direct read against the graph tables' own attributes/edges.

Query params:

| Name       | Required | Description                                                          |
|------------|----------|-----------------------------------------------------------------------|
| `language` | yes      | Matched against MOVIE node `attributes->>'language'` (case-insensitive substring). |
| `movie`    | no       | Additionally filters MOVIE nodes by `attributes->>'name'` (case-insensitive substring). |

Returns MOVIE nodes matching the filters, every USER node connected to them via a `POSTED_ABOUT`
edge, and `RETWEETED` edges among that resolved user set. `404` if `language` matches zero MOVIE
nodes at all; a `movie` filter that matches no titles for an otherwise-valid `language` returns
`200` with empty `nodes`/`edges` instead.

```json
{
  "nodes": [
    { "id": 12, "type": "MOVIE", "attributes": { "managed_entity_id": 21, "name": "Vikram", "language": "Tamil" } },
    { "id": 88, "type": "USER",  "attributes": { "global_user_id": "user-9f2c1e3a-...", "tribe_label": "Cinephile-Critic", "engagement_rating": 87.5 } }
  ],
  "edges": [
    { "id": 401, "from": 88, "to": 12, "relationType": "POSTED_ABOUT", "weight": 6, "timestamp": "2026-04-12T17:05:30" },
    { "id": 402, "from": 88, "to": 91, "relationType": "RETWEETED",    "weight": 2, "timestamp": "2026-04-13T09:11:00" }
  ],
  "summary": { "totalUsers": 1, "totalMovies": 1, "totalEdges": 2 }
}
```

---

### 13. Movie Revenue Prediction Pipeline

A standalone revenue-prediction pipeline for `movies_data_collection`/`actors_data_collection`,
built as a set of Python scripts under `scripts/` plus one Java admin API. Unlike every other
section in this reference, most of this pipeline runs **outside** the Spring Boot app — nothing
in `com.lit.fire.flame` schedules or calls `movie_revenue_impact_model.py` today; it's a `python3`
job you run by hand or from your own scheduler. The one piece that *is* a Java REST API is the
Factor Registry Admin API (Feature 2, below), since that registry is meant to be editable by a
non-Python teammate.

#### 13a. Revenue model script (Feature 0)

**`python3 scripts/movie_revenue_impact_model.py [flags]`**

Predicts theatrical revenue and calibrates business-supplied factor impact bands. Connection
flags mirror `src/main/resources/secrets.txt` (`--db-host`, `--db-port`, `--db-name`, `--db-user`,
`--db-password`, all overridable via `MOVIE_DB_*` env vars).

| Flag | Default | Purpose |
|------|---------|---------|
| `--market {india,global,all}` | `all` | Row set for the primary pipeline. `india` replicates the historical Indian-only filtering; `global`/`all` pool every market with a `market_is_india` feature. The india-only-vs-pooled-vs-per-market comparison in `model_comparison.json` always runs regardless of this flag. |
| `--min-year` / `--min-budget` / `--min-revenue` | `2000` / `10000` / `10000` | Row floors. |
| `--min-feature-coverage` | `5.0` | Feature 2 coverage guard (percent) — see [§13c](#13c-factor-registry-feature-2). |
| `--output-dir` | `./output` | Where every output file below is written. |
| `--use-llm` | off | Asks Claude for qualitative commentary on prior-only (unmeasured) factors; requires `ANTHROPIC_API_KEY`. |

Outputs written to `--output-dir`: `factor_impact_scores.json`/`.csv` (calibrated min/max per
factor), `movie_revenue_predictions.csv` + `revenue_accuracy_summary.json` (out-of-fold accuracy),
`model_comparison.json` (Ridge/GBR/HistGBR/MLP comparison, plus the india/pooled/per-market
breakdown and the `factor_keys_used` list actually trained on that run), `factor_coverage_report.csv`/`.json`
(Feature 2 — every candidate/active factor's coverage % and correlation with `ln(revenue)`),
`factor_impact_scores_nn_calibrated.csv` + `formula_revenue_predictions.csv` +
`formula_accuracy_summary.json` (the SHAP-free MLP partial-dependence calibration and the
`Y = B0 * prod(1+delta_i)` formula reconstruction).

Query `movies_data_collection`'s column list dynamically at startup (`information_schema.columns`)
so a schema-drifted or missing column (e.g. `genres`, `imdb_rating`, `conflict_balance_score`,
`narrative_novelty_score` — none of which exist on the live table today) degrades to an
always-NaN feature instead of crashing the whole run.

#### 13b. Data-source registry + connectors (Feature 1)

Generalizes the per-row URL pattern already on `actors_data_collection`
(`sacnilk_url`/`kulfiy_url`/`fandango_url`) into a `data_sources` table plus three connector
implementations, so adding a new scrape/API/Kaggle source is a data-registration step, not a new
hardcoded column. No REST API — this is a Python-only registry, driven from the CLI or from
`scripts/connectors/schema.register_source(...)` directly. See `scripts/connectors/README.md` for
the full connector reference.

| Script | Purpose |
|--------|---------|
| `python3 scripts/migrate_data_sources.py [--db-*]` | One-time backfill of `data_sources` from the legacy `sacnilk_url`/`kulfiy_url`/`fandango_url` columns on `actors_data_collection`. Safe to re-run (`ON CONFLICT DO NOTHING`). |
| `python3 scripts/collect_data.py --source <name> --entity-type {movie,actor} [--dry-run]` | Loads matching `data_sources` rows, calls the right connector (`html_scrape`/`api`/`kaggle_csv`), upserts the mapped fields onto `movies_data_collection`/`actors_data_collection` (adding columns with `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` as needed), and writes back `last_fetched_at`/`last_status`/`raw_payload`. |

`data_sources` columns: `id, entity_type ('movie'|'actor'), entity_key, source_name, url,
connector_type ('html_scrape'|'api'|'kaggle_csv'), field_mapping (jsonb), last_fetched_at,
last_status, raw_payload (jsonb)`. `entity_key` for a movie is
`movie_name|release_date|language`; for an actor it's the bare `actor_name` — the same
`movie_name|release_date|language` composite is reused by Feature 2's `movie_factor_values.movie_key`
(§13c), so a hand-entered factor value and a scraped source can both be traced to the exact same
`movies_data_collection` row.

#### 13c. Factor Registry (Feature 2)

The live, queryable replacement for the hardcoded 80-entry `FACTOR_CATALOG` list that used to live
inside `movie_revenue_impact_model.py`. Adding a new predictive parameter is now a data-registration
step — insert one `factor_definitions` row, supply values, and the next run picks it up
automatically — instead of hand-editing `feature_columns()`/`assemble_features()`.

**CLI (Python):**

| Script | Purpose |
|--------|---------|
| `python3 scripts/migrate_factor_definitions.py [--db-*] [--no-overwrite-status]` | One-time seed of `factor_definitions` from the original 80-factor catalogue (preserved in `scripts/registry/seed_catalog.py`). Safe to re-run; `--no-overwrite-status` preserves a factor's status if it's already been promoted/deprecated by hand since the last seed. |
| `python3 scripts/register_factor.py --key <key> --name <name> --category <cat> --direction {Positive,Negative,Bidirectional} --stated-min <f> --stated-max <f> [--data-type {numeric,boolean,categorical}] [--status {candidate,active,deprecated,explanatory_only}] [--source-table <t>] [--source-column <c>] [--computation-type {raw_column,derived_sql,derived_python_fn,eav}] [--derivation-ref <ref>] [--notes <text>]` | Upserts one `factor_definitions` row. |
| `python3 scripts/register_factor.py --key <key> --status active --promote-only` | Changes only `status` on an existing factor (the candidate→active / active→deprecated promotion path). |

**Coverage guard:** a `candidate`/`active` factor is only included in a training run's feature set
once its non-null coverage on that run's rows clears `--min-feature-coverage` (default 5%) —
below-threshold `active` factors, and every `candidate` factor regardless of coverage, are still
computed and reported in `factor_coverage_report.csv`/`.json`, just excluded from the trained model.

**Java Admin API (`/api/admin`)** — `FactorDefinitionController`, for a non-Python teammate to
register/promote a factor or hand the system a spreadsheet of scores without touching Python or
the Postgres console:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/admin/factor-definitions?status=<status>` | Lists `factor_definitions` rows, optionally filtered by `status`. |
| `GET /api/admin/factor-definitions/{key}` | Fetches one factor by `factorKey`; `404` if it doesn't exist. |
| `POST /api/admin/factor-definitions` | Creates or updates (upsert-on-`factorKey`) one factor. |
| `PATCH /api/admin/factor-definitions/{key}/status` | Changes only `status` — the promote/deprecate path. |
| `POST /api/admin/factor-values` | Bulk-upserts `movie_factor_values` rows — the "hand the system a spreadsheet of scores" path for a factor with no dedicated column. |
| `GET /api/admin/factor-definitions/status-counts` | `{status: count}` across `factor_definitions` — e.g. `{"active": 12, "candidate": 62, "deprecated": 3, "explanatory_only": 3}`. |

**`POST /api/admin/factor-definitions`**

```json
{
  "factorKey": "ticket_price_atp",
  "name": "Average Ticket Price",
  "category": "Financial",
  "direction": "Positive",
  "statedMin": 0.15,
  "statedMax": 0.25,
  "dataType": "numeric",
  "status": "candidate",
  "sourceTable": "ticket_price_index",
  "sourceColumn": "atp_usd",
  "computationType": "raw_column",
  "derivationRef": null,
  "addedBy": "jdoe",
  "notes": "Hand-curated from PVR Inox / FICCI-EY quarterly reports"
}
```

`factorKey`, `name`, `category`, `statedMin`, `statedMax` are required. `direction` (if present)
must be one of `Positive`/`Negative`/`Bidirectional`; `dataType` one of
`numeric`/`boolean`/`categorical` (default `numeric`); `status` one of
`candidate`/`active`/`deprecated`/`explanatory_only` (default `candidate`); `computationType` one
of `raw_column`/`derived_sql`/`derived_python_fn`/`eav`. A request violating any of these returns
`400` with an `errors` array. Response: `200 OK` with `{"factorKey": ..., "status": ...}`.

**`PATCH /api/admin/factor-definitions/{key}/status`**

```json
{ "status": "active" }
```

`200 OK` with `{"factorKey": ..., "status": ...}` on success; `400` for an invalid `status` value;
`404` if `{key}` has no existing row (this endpoint only changes status on a factor that's already
been registered — it does not create one).

**`POST /api/admin/factor-values`**

```json
{
  "values": [
    { "movieKey": "Dune: Part Two|2024-03-01|english", "factorKey": "ticket_price_atp", "valueNumeric": 0.42 },
    { "movieName": "Vikram", "releaseDate": "2022-06-03", "language": "tamil", "factorKey": "ticket_price_atp", "valueNumeric": 0.31 }
  ]
}
```

Each entry needs either `movieKey` directly, or `movieName`+`releaseDate`+`language` (the same
composite Feature 1's `data_sources.entity_key` uses — the endpoint builds `movieKey` from them),
plus `factorKey` and one of `valueNumeric`/`valueText`. Re-posting the same `(movieKey, factorKey)`
updates the existing row in place (`ON CONFLICT ... DO UPDATE`) rather than duplicating it. `400`
if any entry is missing an identifier, a `factorKey`, or a value; `200 OK` with
`{"upserted": <n>}` on success.

Note the movie-key format mismatch to watch for: `movie_factor_values.movie_key` (and
`data_sources.entity_key`) is the **canonical per-row** `movie_name|release_date|language`
composite — matching `movies_data_collection`'s own primary key — whereas
`movie_revenue_impact_model.py`'s *internal* `movie_key` (used for post-dedup joins like
`actors_by_movie`) is a coarser `lowercased-name|release_year` key with no language component,
since `dedupe_movies()` collapses every dubbed-language release of a film into one training row.
The script translates between the two automatically when resolving an `eav`-typed factor; API
callers only ever need the canonical three-part form shown above.

