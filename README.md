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
   - [Viral Seeds & Aspect Drivers (`/api/marketing`)](#6-viral-seeds--aspect-drivers)
   - [Top Spreaders (`/api/marketing/top-50-spreaders`)](#7-top-spreaders)
   - [Lookalike Discovery (`/api/marketing/find-lookalikes`)](#8-lookalike-discovery)
   - [Enrichment Admin (`/api/admin`)](#9-enrichment-admin-api)
   - [Diagnostic / Test (`/test`, `/api/test`)](#10-diagnostic--test-endpoints)
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
```

Defaults if `secrets.txt` is absent: `jdbc:postgresql://localhost:5432/aura`, user `postgres`,
empty password. **Do not commit real secrets.**

Scheduled jobs (enabled via `@EnableScheduling` on `AuraMathApplication`):
- `AuthorCategoryController.scheduledResync()` — re-runs `/api/marketing/users/sync` every
  24h, with a 5-minute startup delay.

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
| `opportunityFlags`         | Specific openings (e.g. `Brand Evangelist`, `Keyword Anchor Window`). |

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
| `audienceClassification` | `Brand Evangelist`   |
| `influenceTier`          | `Viral Node`         |
| `postingStyle`           | `Power Burst Poster` |
| `dominantTone`           | `positive`           |
| `primaryPlatform`        | `x`                  |

Response:

```json
{
  "filtersApplied": { "audience_classification": "Brand Evangelist" },
  "totalUsers": 3,
  "users": [ { "author": "janedoe", "influence_tier": "Amplifier", "..." } ]
}
```

**`GET /api/marketing/users/categories`**

Returns the distinct values present in each categorical column — useful to populate
filter dropdowns in a UI.

```json
{
  "audience_classification": ["Brand Evangelist", "Critical Power Influencer", "Neutral Informer"],
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

**`GET /api/marketing/genre/{genre}/potential-viewers`**

Users whose `top_genres[{genre}]` > `0.7`, sorted by predicted conversion probability
`p_conv = sigmoid(genre_interest_score * influence_rank)`.

```json
{
  "genre": "horror",
  "threshold": 0.7,
  "scoringModel": "p_conv = 1 / (1 + exp(-(genre_interest_score * influence_rank)))",
  "totalViewers": 27,
  "viewers": [
    {
      "global_user_id": "u_182374",
      "tribe_label": "Cinephile-Critic",
      "platform_handles": { "primary_platform": "x", "by_platform": { "x": {"profile_url": "..."} } },
      "peak_activity_times": { "hours": [21,22,23] },
      "genre_interest_score": 0.91,
      "influence_rank": 0.84,
      "moi_score": 12.7,
      "p_conv": 0.683
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

**`GET /api/marketing/celebrity/{celebrity}/super-fans`**

Top 50 users who posted about the celebrity keyword, ranked by Hawkes α. Same
shape as the party spreaders endpoint with `spreaders` → `superFans` and
`totalSpreaders` → `totalSuperFans`.

**`GET /api/marketing/celebrity/{celebrity}/channel-strategy`**

Same shape as `/party/{party}/channel-strategy` with `party` replaced by
`celebrity`. Reach proxies are identical.

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

---

### 7. Top Spreaders

**`GET /api/marketing/top-50-spreaders/{keyword}`**

Top 50 authors on **X** for posts matching `{keyword}` in the last 90 days, ranked by
**Viral Potential Score**:

```
VPS = (likes + 3 × comments) × (1 + α)
```

Engagement count rewards authors whose audience actively reacts (not just passive viewers).
The `(1 + α)` factor lets Hawkes infectivity boost bursty cascade-starters without zeroing
out high-engagement organic spreaders whose cadence fits α ≈ 0. Comments are weighted 3×
likes (more user effort, stronger sharing signal). Requires at least 2 matching posts per
author.

```json
[
  {
    "author": "janedoe",
    "viral_potential_score": 759.0,
    "alpha": 0.0,
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

---

### 9. Enrichment Admin API

**`POST /api/admin/run-enrichment`**

Synchronously triggers `MarketingEnrichmentEngine.enrichAndSave()`, which recomputes
the entire `marketing_target_profiles` table. **Long-running** — call from a job runner
or admin tool, not from request-path code.

Response: `200 OK` with body `"done"`.

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
requests may skip tables/columns. The engine is currently scaffolding only (F0) and exposes **no
endpoints yet** — see [`docs/ask-engine/DESIGN.md`](docs/ask-engine/DESIGN.md) for the pipeline,
guarantees, and the F0–F11 roadmap.

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
| positive + BR ≥ 0.7    | `Brand Evangelist`             |
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

