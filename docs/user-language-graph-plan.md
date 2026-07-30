# Feature plan: language/movie-filterable user graph + engagement rating

Verified against the live `aura` Postgres database on 2026-07-29 (not just grepped from
code — every table/column below was confirmed with `\d` and sample queries). Feed the
prompts to Claude Code **in order** — each one depends on the previous.

## Ground truth this plan is built on

- `mentions` (42,883 rows) — `id, managed_entity_id, platform, post_id, content, author,
  post_date, sentiment, sentiment_score, permalink, sentiment_category`.
- `mention_entities` (mention_id, managed_entity_id) — many-to-many join table. Confirmed
  it covers **all** 42,883 mentions (superset of the direct `mentions.managed_entity_id`
  FK), so it's the correct join, matching what `GenreMarketingAPI`/`MarketingEnrichmentEngine`
  already do.
- `managed_entities` — `id, name, type (MOVIE|CELEBRITY), director, release_date, genre,
  industry, language, owner_id, synopsis, budget, ...`. **`language` already exists**
  (Kannada/Tamil/Telugu/Hindi/Malayalam confirmed present) — no schema change needed for
  the language filter, contrary to what I assumed before checking the live DB.
- `entity_keywords` — also has a `language` column (denormalized copy), plus `category`
  (`media.movie` / `media.celebrity`).
- Per-platform raw tables and their engagement columns:
  - `x_posts`: `comment_count, likes_count, views_count` (no shares column)
  - `youtube_comments`: `reply_count, likes_count` (no views, no shares — these rows are
    comments on a video, not the video itself)
  - `reddit_posts`: `num_comments, score` (`score` = net upvotes, already used elsewhere
    as the likes-proxy for Reddit)
  - `instagram_posts`: `comments_count, like_count` (no views, no shares)
- **Retweets on X**: confirmed live format is `RT @<handle>: <original text>` (classic
  Twitter scrape format) — **not** the `[RT]` bracket tag assumed earlier. 23,748 of
  69,095 `x_posts` rows (34%) match `text ILIKE 'RT @%'`. Use this pattern, not `[RT]%`.
- **User identity resolution already exists**: `user_identity_link(global_user_id,
  normalized_author)` maps `REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g')` →
  `"user-" + UUID`, built by `CrossPlatformIdentityResolver` from the union of
  `x_posts/youtube_comments/reddit_posts/instagram_posts` authors. **`mentions.author` is
  not itself normalized** and must go through the same `normalize()` step before joining
  — `GenreInterestProfiler.java` and `GenreLookalikeService.java` do this correctly.
  (Side note, not in scope to fix here: `GenreMarketingAPI.potentialViewers` joins
  `marketing_target_profiles.global_user_id = mentions.author` directly, skipping
  normalization — almost certainly a latent bug. Don't copy that pattern into new code;
  follow `GenreLookalikeService.normalize()` instead.)
- `graph_nodes` (`id, attributes jsonb, type, owner_id`) and `graph_edges` (`id,
  from_node_id, relation_type, timestamp, to_node_id, weight`) **already exist in the
  schema** — `type` is constrained to `USER|MOVIE|ACTOR|SONG|TRAILER|ASPECT|CHECKPOINT|
  DATE|LOCATION`, `relation_type` to `POSTED_ABOUT|WATCHED|LIKED|RETWEETED|PROMOTED|
  MENTIONED|OCCURRED_ON`. Both tables are **currently empty**, and no Java class writes
  to them. This is unused scaffolding clearly meant for exactly this feature — use it
  instead of inventing a parallel structure.
- Decided scope: **backend APIs only**. No frontend in this repo — the graph endpoint
  returns nodes+edges JSON for you to render elsewhere.

---

## Feature 1 — Resolve X retweets into a shares signal

**Why first:** the engagement formula needs a real `shares` number, and nothing in the
schema currently tracks it. Retweets are identifiable from `x_posts.text` starting with
`RT @<handle>:`.

**Prompt:**
> In this Spring Boot repo, add retweet resolution for X posts. `x_posts.text` for
> retweets starts with `RT @<handle>: <original text...>` (confirm the exact regex
> against live data first — roughly 34% of rows match `text ILIKE 'RT @%'`). Add a new
> service class `RetweetResolver` (package `com.lit.fire.flame`, follow the style of
> `ConflictBalanceService`/`NarrativeNoveltyService` — `ensureSchema()` with `ALTER TABLE
> IF NOT EXISTS`, then a `recomputeAndPersist()` method) that:
> 1. Adds `shares_count INT DEFAULT 0` to `x_posts` (idempotent `ALTER TABLE ADD COLUMN
>    IF NOT EXISTS`).
> 2. For every row matching `RT @(\w+):`, extracts the retweeted handle and the quoted
>    text remainder.
> 3. Attributes the retweet as a "share" of the retweeted handle's content:
>    - **Primary output** (required): a per-author aggregate — for each retweeted
>      handle (normalized the same way `CrossPlatformIdentityResolver` does:
>      `lower(author)` with non-alphanumerics stripped), count how many `RT @handle:`
>      rows reference them. This is the number used later for the per-user engagement
>      rating (Feature 3) and does not depend on the original tweet being present in
>      `x_posts`.
>    - **Best-effort secondary output**: where a specific original row *can* be
>      confidently matched (same normalized author, `created_at` earlier than the RT,
>      and the RT's quoted-text remainder is a prefix/substring match — use
>      `pg_trgm`'s `similarity()` if the extension is available, else a `LIKE` prefix
>      match on the first ~40 chars), increment that row's `shares_count`. Rows with no
>      confident match keep `shares_count = 0` — do not guess.
> 4. Exposes the per-author aggregate as a public method other services can call (don't
>    just persist it to a column and force everyone to re-query — Feature 3 needs it
>    directly).
> Write a unit test with a handful of synthetic `x_posts` rows (some retweets, some
> originals, one retweet with no matching original in the table) verifying both outputs.

---

## Feature 2 — Cross-platform post engagement score

**Prompt:**
> Add a pure, unit-testable class `EngagementScoreCalculator` (package
> `com.lit.fire.flame`, same style as `InfluenceMetricCalculator`) with:
> ```java
> static double score(double comments, double shares, double likes, double views) {
>     return 3.0 * comments + 2.0 * shares + 1.5 * likes + 1.0 * views;
> }
> ```
> This is the fixed weighting the user specified (comments:shares:likes:views =
> 3:2:1.5:1) — do not normalize/log-scale inside this method; keep it a raw linear
> combination so callers can decide separately whether to rank on raw totals or a
> percentile band (see Feature 3).
>
> Then add per-platform adapter methods that map each raw table's columns onto
> `score(...)`, since the platforms don't all track the same things:
> - `x_posts`: comments=`comment_count`, shares=`shares_count` (from Feature 1, default
>   0 if the column/row predates that migration), likes=`likes_count`, views=`views_count`.
> - `youtube_comments`: comments=`reply_count`, likes=`likes_count`, shares=0, views=0
>   (a YouTube row here is a *comment on a video*, not the video itself — there's no
>   view/share count to attach to it; document this limitation in a comment).
> - `reddit_posts`: comments=`num_comments`, likes=`score` (Reddit's net-upvote column,
>   already used as the likes-proxy elsewhere in this codebase — see the comment in
>   `RawMappingDiagnosticController`), shares=0, views=0.
> - `instagram_posts`: comments=`comments_count`, likes=`like_count`, shares=0, views=0.
>
> Add a unit test per platform adapter confirming the weighted math and that missing
> fields default to 0 rather than throwing.

---

## Feature 3 — Per-user aggregate engagement rating (persisted)

**Prompt:**
> Add a service `UserEngagementRatingService` (package `com.lit.fire.flame`) that
> computes one engagement rating per resolved user and persists it to
> `marketing_target_profiles`, following the exact precompute-and-persist pattern used
> by `ConflictBalanceService`/`NarrativeNoveltyService` (percentile-banded score) and
> `MarketingEnrichmentScheduler` (cron + on-demand admin trigger).
>
> Steps:
> 1. `ensureSchema()`: `ALTER TABLE marketing_target_profiles ADD COLUMN IF NOT EXISTS
>    engagement_score_raw double precision, ADD COLUMN IF NOT EXISTS engagement_rating
>    double precision`.
> 2. For each of `x_posts, youtube_comments, reddit_posts, instagram_posts`, select every
>    row with a non-null `author`, resolve it to a `global_user_id` via
>    `user_identity_link` **using the same `normalize()` logic as
>    `GenreLookalikeService`** (lowercase, strip non-alphanumerics) — do not copy the
>    unnormalized direct-match pattern from `GenreMarketingAPI.potentialViewers`. Rows
>    with no matching identity are skipped (same behavior as `GenreLookalikeService`).
> 3. For every row, compute its engagement score via `EngagementScoreCalculator`'s
>    per-platform adapter (Feature 2), and sum per `global_user_id` →
>    `engagement_score_raw`.
> 4. Convert `engagement_score_raw` into a comparable `engagement_rating` using the same
>    median + percentile-rank banding as `ConflictBalanceService` (`median()`,
>    `percentileRank()`, `BAND_FLOOR`/`BAND_CEIL` — reuse or copy those exact helpers),
>    so users end up ranked 0–100 relative to each other rather than on an unbounded raw
>    sum. Pick your own floor/ceiling band (e.g. 0–100) and note the choice in a comment.
> 5. `UPDATE marketing_target_profiles SET engagement_score_raw = ?, engagement_rating =
>    ? WHERE global_user_id = ?` for every resolved user; if a `global_user_id` has no
>    existing `marketing_target_profiles` row, skip it (that table is populated by
>    `MarketingEnrichmentEngine` separately — don't create rows here).
> 6. Wire it into the existing `MarketingEnrichmentScheduler` cron (or add a sibling
>    scheduled method) and add a `POST /api/admin/run-engagement-rating` trigger
>    endpoint mirroring the existing `/api/admin/run-enrichment`.
>
> Add a test with synthetic rows across at least two platforms for the same resolved
> user, confirming the raw sum and that the banding stays within the configured range.

---

## Feature 4 — "Users who engaged with any movie in language X"

**Prompt:**
> Add `GET /api/marketing/language/{language}/users` (new controller
> `LanguageMarketingAPI`, package `com.lit.fire.flame`, same shape as
> `GenreMarketingAPI`). Given a language path variable (e.g. `Tamil`), return every
> distinct user who has a mention linked to any `managed_entities` row where
> `type = 'MOVIE'` and `language ILIKE ?`. Query path:
> `mentions m JOIN mention_entities me_j ON me_j.mention_id = m.id JOIN
> managed_entities me ON me.id = me_j.managed_entity_id WHERE me.type = 'MOVIE' AND
> me.language ILIKE ?`. Resolve `m.author` to `global_user_id` the same way as Feature 3
> (normalize + `user_identity_link`) — skip mentions whose author has no resolved
> identity, same as elsewhere in this codebase.
>
> For each distinct user, return: `global_user_id`, `mention_count` (how many
> mentions of language-X movies they have), `distinct_movies_mentioned` (count), and
> `engagement_rating` / `tribe_label` / `platform_handles` joined from
> `marketing_target_profiles` (LEFT JOIN — don't drop users just because enrichment
> hasn't run for them yet; null those fields instead). Sort by `engagement_rating`
> DESC NULLS LAST. Include `totalUsers` in the response body, matching the
> `{genre, totalViewers, viewers}` shape `GenreMarketingAPI.potentialViewers` already
> uses. Case-insensitive language match; return an empty (not 404) list for an unknown
> language.

---

## Feature 5 — "How many users of language X commented on movie Y"

**Prompt:**
> Add `GET /api/marketing/language/{language}/movie/{movieName}/users` to the same
> `LanguageMarketingAPI` controller. Same join as Feature 4, but additionally filter
> `me.name ILIKE ?` for the movie name. Return `{language, movie, totalUsers, users:
> [...]}` where each user entry has the same shape as Feature 4 plus a
> `mentions_on_this_movie` count and `average_sentiment_score` for that user on that
> movie specifically (from `mentions.sentiment_score`, same 1–100 bounds check the rest
> of the codebase uses — see `GenreMarketingAPI`'s `sentiment_score BETWEEN 1 AND 100`).
> If the movie name doesn't resolve to any `managed_entities` row with that language,
> return `totalUsers: 0` and an empty list rather than an error — the caller may be
> probing an unreleased/untracked title.

---

## Feature 6 — Populate the graph (`graph_nodes` / `graph_edges`)

**Prompt:**
> `graph_nodes` and `graph_edges` already exist in this schema (`type` constrained to
> USER/MOVIE/ACTOR/SONG/TRAILER/ASPECT/CHECKPOINT/DATE/LOCATION; `relation_type` to
> POSTED_ABOUT/WATCHED/LIKED/RETWEETED/PROMOTED/MENTIONED/OCCURRED_ON) but are currently
> empty and unpopulated by any code. Add a `GraphPopulationService` (package
> `com.lit.fire.flame`) that builds them from the mentions/engagement data, following
> the same precompute-and-persist + scheduler pattern as Feature 3.
>
> 1. **MOVIE nodes**: one `graph_nodes` row per `managed_entities` row where
>    `type = 'MOVIE'`. `attributes` jsonb = `{"managed_entity_id": ..., "name": ...,
>    "language": ..., "genre": ..., "release_date": ...}`. `owner_id` =
>    `managed_entities.owner_id` (tenant-scoped, same owner as the entity). Upsert
>    keyed on `attributes->>'managed_entity_id'` — add a helper query to find an
>    existing node before inserting a duplicate, since `graph_nodes` has no unique
>    constraint on that field.
> 2. **USER nodes**: one `graph_nodes` row per resolved `global_user_id` that appears in
>    `mentions` (via the Feature 4 join, i.e. only users who mentioned a MOVIE entity —
>    don't create nodes for every author in the raw platform tables). `attributes` =
>    `{"global_user_id": ..., "engagement_rating": ..., "tribe_label": ...}` (pull the
>    last two from `marketing_target_profiles`, null if absent). **Set `owner_id` to
>    NULL** — a social-media user isn't owned by one tenant the way a movie entity is,
>    and the same author can be relevant to multiple entity owners. Flag this decision
>    explicitly in a code comment so it's easy to revisit if a tenant-scoping
>    requirement shows up later.
> 3. **POSTED_ABOUT edges**: for every (user, movie) pair with at least one mention,
>    one `graph_edges` row from the USER node to the MOVIE node. `weight` = that user's
>    summed `EngagementScoreCalculator` score across just their posts about that movie
>    (not their global rating — this edge is movie-specific). `timestamp` = the most
>    recent `mentions.post_date` for that pair.
> 4. **RETWEETED edges**: using Feature 1's per-author retweet aggregate, one
>    `graph_edges` row from the retweeting user's node to the retweeted user's node
>    (both must already exist as USER nodes from step 2 — skip pairs where either side
>    isn't in the graph). `weight` = retweet count between that pair.
> 5. Make the whole population idempotent (safe to re-run): delete-and-rebuild edges for
>    a given owner scope, or upsert by matching on `(from_node_id, to_node_id,
>    relation_type)`, your choice — document which.
> 6. Wire into the existing scheduler pattern (cron + `POST
>    /api/admin/run-graph-population` trigger).
>
> Add a test with a small synthetic mentions/managed_entities fixture verifying node and
> edge counts.

---

## Feature 7 — Filterable graph query API

**Prompt:**
> Add `GET /api/graph/users?language={language}` and `GET
> /api/graph/users?language={language}&movie={movieName}` (new controller
> `UserGraphController`, package `com.lit.fire.flame`) returning the graph in
> `{nodes: [...], edges: [...]}` shape, ready for any graph-viz frontend to consume
> directly (D3/Cytoscape/vis.js all accept roughly this shape).
>
> - `nodes`: every `graph_nodes` row of type `MOVIE` whose `attributes->>'language'`
>   matches (case-insensitive), plus (if `movie` is given) further filtered to
>   `attributes->>'name' ILIKE`; and every `USER`-type node reachable from those MOVIE
>   nodes via a `POSTED_ABOUT` edge. Each node in the response: `{id, type, attributes}`.
> - `edges`: every `POSTED_ABOUT` edge between the returned MOVIE and USER nodes, plus
>   (optional, include if not expensive) `RETWEETED` edges between the returned USER
>   nodes, so the response can also show amplification structure among the filtered
>   audience. Each edge: `{id, from, to, relationType, weight, timestamp}`.
> - Response also includes `summary: {totalUsers, totalMovies, totalEdges}` — this is
>   what directly answers "how many users of language X commented on movie Y" when both
>   filters are given, without the caller having to count nodes client-side.
> - Reuse `GraphPopulationService`'s query helpers where possible rather than
>   re-deriving the join logic a third time (Features 4/5/7 all filter the same
>   mentions→managed_entities relationship — factor the shared SQL into one place if it
>   starts duplicating across controllers).
> - 404 (not empty) only when the language itself matches zero MOVIE nodes at all —
>   distinguishes "no such language" from "language exists, zero users match".

---

## Suggested order to hand these to Claude Code

1 → 2 → 3 → 4 → 5 → 6 → 7. Each is independently testable before moving to the next;
4 and 5 can be done in either order relative to each other but both need 3 done first
(for `engagement_rating`), and 6/7 need 1–5 done since they reuse those services.
