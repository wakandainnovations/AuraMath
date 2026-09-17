#!/usr/bin/env python3
"""india_movie_data_entry_ui.py -- local web UI for filling in the data gaps in
`india_movies_data_collection` (the TMDB-sourced India-only movie catalog -- see
IndiaMoviesImportService / IndiaMoviesCopyService in the AuraDataFiller repo, an
exact `LIKE ... INCLUDING ALL` schema mirror of movies_data_collection keyed on
(movie_name, release_date, language)).

Companion to data_entry_ui.py (movies_data_collection, port 3030) -- same list/
search/completeness-bar/edit-form conventions, same identity-rename-with-PK-
collision-check save logic, same local-only Flask app. Two differences:

  * No actors_data_collection / movie_marketing_tactics sections here: both of
    those tables key off movies_data_collection identities, not this table's --
    out of scope for this catalog.
  * A "Video snapshot matches" panel on the edit page, sourced from
    `movie_video_snapshots` (see
    AuraPredictor/aurapredictor/enrichment/video_snapshots.py). That table holds
    Wayback-Machine-reconstructed trailer/teaser YouTube view counts at two fixed
    checkpoints (7 days before release, and on release day) -- a leakage-free
    alternative to a live YouTube API call, which only ever returns *today's*
    cumulative view count. Its movie_name/release_date don't always line up
    exactly with this table's (diacritics, TMDB-vs-crawler release-date drift --
    e.g. "Baahubali: The Epic" vs "Bāhubali: The Epic", two days apart), so
    candidates are found by trigram name similarity rather than an exact join,
    and shown for the curator to visually confirm -- "Apply" only fills the form
    fields client-side, it never writes to the DB on its own; a real Save still
    requires the explicit Save button below.

Requirements
------------
    pip install flask psycopg2-binary

Usage
-----
    python3 india_movie_data_entry_ui.py --db-host localhost --db-name aura \\
        --db-user mukundv --http-port 3033

Then open http://127.0.0.1:3033/ -- binds to 127.0.0.1 by default (not 0.0.0.0),
i.e. local-machine-only unless you pass --http-host 0.0.0.0 yourself.
"""
from __future__ import annotations

import argparse
import os
import re
from datetime import date, datetime
from typing import Optional

import psycopg2
import psycopg2.extras
from flask import Flask, redirect, render_template_string, request, url_for

app = Flask(__name__)
DB_ARGS: dict = {}
PAGE_SIZE = 50
TABLE = "india_movies_data_collection"
SNAPSHOT_TABLE = "movie_video_snapshots"

# Below this trigram similarity, a movie_video_snapshots row isn't shown as a
# candidate at all -- keeps the panel to plausible matches instead of noise.
SNAPSHOT_SIMILARITY_THRESHOLD = 0.3

# ---------------------------------------------------------------------------
# Field catalogue -- every column below is a real, actively-populated column on
# india_movies_data_collection (verified against live data, not every column on
# the mirrored schema: e.g. `runtime_mins`/`genres` are legacy duplicates of
# `runtime`/`genre` that TMDB import never writes, and the lead_prior_*/
# director_prior_*/ensemble_avg_prior_hit_rate/*_last_checked columns are
# bookkeeping for other automated services, not hand-entry targets).
# ---------------------------------------------------------------------------
CORE_FIELDS = [
    ("genre", "Genre", "text"),
    ("country", "Country", "text"),
    ("remake_of", "Remake of (original movie's name, or NOT_REMAKE)", "text"),
    ("directors", "Director(s)", "text"),
    ("production_companies", "Production companies (comma-separated)", "text"),
    ("runtime", "Runtime (minutes)", "number"),
    ("number_of_screens", "Number of screens (release)", "number"),
    ("budget", "Budget (USD)", "number"),
    ("revenue", "Revenue (USD)", "number"),
    ("rating_10", "IMDB rating (0-10)", "number"),
    ("cbfc_rating", "CBFC rating (U / UA / A)", "text"),
    ("release_event_type", "Release event type", "text"),
    ("release_event_name", "Release event name", "text"),
    ("release_event_detail", "Release event detail", "text"),
]

MARKETING_FIELDS = [
    ("trailer_release_date", "Trailer release date (YYYY-MM-DD)", "text"),
    ("trailer_views", "Trailer views (cumulative, as of release day)", "number"),
    ("trailer_views_7d_prior_to_release", "Trailer views (7 days before release)", "number"),
    ("trailer_comments", "Trailer comments", "number"),
    ("teaser_release_date", "Teaser release date (YYYY-MM-DD)", "text"),
    ("teaser_views", "Teaser views (cumulative, as of release day)", "number"),
    ("teaser_views_7d_prior_to_release", "Teaser views (7 days before release)", "number"),
    ("teaser_comments", "Teaser comments", "number"),
    ("first_song_release_date", "First song release date (YYYY-MM-DD)", "text"),
    ("song_views", "Song views", "number"),
    ("song_comments", "Song comments", "number"),
]

TEXT_COLS = ["genre", "country", "directors", "production_companies", "cbfc_rating",
             "release_event_type", "release_event_name", "release_event_detail",
             "trailer_release_date", "teaser_release_date", "first_song_release_date"]
NUM_COLS = ["runtime", "number_of_screens", "budget", "revenue", "rating_10",
            "trailer_views", "trailer_views_7d_prior_to_release", "trailer_comments",
            "teaser_views", "teaser_views_7d_prior_to_release", "teaser_comments",
            "song_views", "song_comments"]

# Tracked for the completeness bar / "incomplete only" filter -- the fields a
# curator is actually expected to fill in, not every column on the row.
COMPLETENESS_COLS = ["genre", "directors", "production_companies", "runtime",
                      "rating_10", "cbfc_rating", "budget", "revenue"]

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

def get_conn():
    return psycopg2.connect(**DB_ARGS)


def ensure_schema(conn) -> None:
    """pg_trgm powers the fuzzy movie_video_snapshots matching below (similarity())
    -- already installed on this DB (india_movies_data_collection's own indexes use
    gist_trgm_ops), this is just a defensive no-op guard so a fresh DB doesn't 500
    on first request.

    trailer_views_7d_prior_to_release / teaser_views_7d_prior_to_release hold the
    same leakage-free pre-release checkpoint movie_video_snapshots.views_7d_before_release
    carries, as their own columns alongside the existing trailer_views/teaser_views
    (which represent the cumulative count as of release day) -- so both numbers can
    be entered/stored side by side instead of one overwriting the other. Idempotent,
    safe to run on every startup."""
    with conn.cursor() as cur:
        cur.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
        cur.execute(f"ALTER TABLE {TABLE} ADD COLUMN IF NOT EXISTS "
                    f"trailer_views_7d_prior_to_release bigint")
        cur.execute(f"ALTER TABLE {TABLE} ADD COLUMN IF NOT EXISTS "
                    f"teaser_views_7d_prior_to_release bigint")
    conn.commit()


def parse_num(raw: Optional[str]):
    if raw is None or str(raw).strip() == "":
        return None
    try:
        return float(raw)
    except ValueError:
        return None


def parse_release_date(s: Optional[str]) -> Optional[date]:
    if not s or not DATE_RE.match(s.strip()):
        return None
    try:
        return datetime.strptime(s.strip(), "%Y-%m-%d").date()
    except ValueError:
        return None


def days_between(later_str: Optional[str], earlier_str: Optional[str]) -> Optional[int]:
    """later - earlier, in days -- either side missing/not a full YYYY-MM-DD leaves
    this NULL rather than guessing."""
    later = parse_release_date(later_str)
    earlier = parse_release_date(earlier_str)
    if later is None or earlier is None:
        return None
    return (later - earlier).days


def movie_completeness(row: dict) -> tuple[int, int]:
    filled = sum(1 for c in COMPLETENESS_COLS if row.get(c) not in (None, "", 0))
    return filled, len(COMPLETENESS_COLS)


def fetch_snapshot_candidates(conn, movie_name: str, release_date: str) -> list[dict]:
    """Trigram-similarity candidates from movie_video_snapshots for one movie --
    see the module docstring for why this isn't a plain exact join. release_date
    is used only to compute a display-only "days apart" hint, not to filter."""
    target_date = parse_release_date(release_date)
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(
            f"""
            SELECT movie_name, release_date, language, video_type, youtube_video_id,
                   published_at, views_7d_before_release, views_on_release,
                   similarity(lower(trim(movie_name)), lower(trim(%(name)s))) AS sim
            FROM {SNAPSHOT_TABLE}
            WHERE similarity(lower(trim(movie_name)), lower(trim(%(name)s))) > %(threshold)s
               OR lower(trim(movie_name)) = lower(trim(%(name)s))
            ORDER BY sim DESC, video_type
            LIMIT 10
            """,
            {"name": movie_name, "threshold": SNAPSHOT_SIMILARITY_THRESHOLD},
        )
        rows = cur.fetchall()

    candidates = []
    for r in rows:
        snap_date = parse_release_date(r["release_date"])
        date_diff = (target_date - snap_date).days if target_date and snap_date else None
        published_at8 = (r["published_at"] or "")[:10]
        candidates.append({
            **r,
            "date_diff_days": date_diff,
            "published_at8": published_at8,
            "views_7d_str": "" if r["views_7d_before_release"] is None else str(r["views_7d_before_release"]),
            "views_on_release_str": "" if r["views_on_release"] is None else str(r["views_on_release"]),
        })
    return candidates


# ---------------------------------------------------------------------------
# Templates
# ---------------------------------------------------------------------------

LIST_TEMPLATE = """
<!doctype html><html><head><title>India movie data entry</title>
<style>
 body { font-family: -apple-system, Helvetica, Arial, sans-serif; margin: 2rem; color: #1a1a1a; background: #fafafa; }
 h1 { font-size: 1.3rem; }
 table { border-collapse: collapse; width: 100%; background: #fff; }
 th, td { border-bottom: 1px solid #e2e2e2; padding: 6px 10px; text-align: left; font-size: 0.9rem; }
 th { background: #f0f0f0; }
 tr:hover { background: #f5f8ff; }
 a { color: #2453b8; text-decoration: none; }
 a:hover { text-decoration: underline; }
 .bar { display:inline-block; width:60px; height:8px; background:#e2e2e2; border-radius:4px; overflow:hidden; vertical-align:middle; }
 .fill { display:block; height:100%; background:#4a8f4a; }
 .controls { margin-bottom: 1rem; }
 .controls input[type=text] { padding: 5px 8px; width: 260px; }
 .controls button { padding: 5px 12px; }
 .pager { margin-top: 1rem; }
 .pager a { margin-right: 12px; }
 .badge { font-size: 0.75rem; color: #888; }
</style></head><body>
<h1>India movie data entry ({{ total }} movies, ordered latest &rarr; oldest)</h1>
<div class="controls">
  <form method="get">
    <input type="text" name="q" placeholder="Search movie name..." value="{{ q }}">
    <select name="language" onchange="this.form.submit()">
      <option value="">All languages</option>
      {% for lang in languages %}
      <option value="{{ lang }}" {{ 'selected' if lang == language else '' }}>{{ lang }}</option>
      {% endfor %}
    </select>
    <select name="year" onchange="this.form.submit()">
      <option value="">All years</option>
      {% for yr in years %}
      <option value="{{ yr }}" {{ 'selected' if yr == year else '' }}>{{ yr }}</option>
      {% endfor %}
    </select>
    <label><input type="checkbox" name="incomplete" value="1" {{ 'checked' if incomplete else '' }} onchange="this.form.submit()"> incomplete only</label>
    <button type="submit">Search</button>
  </form>
</div>
<table>
<tr><th>Release date</th><th>Movie</th><th>Language</th><th>Country</th><th>Completeness</th><th></th></tr>
{% for m in movies %}
<tr>
  <td>{{ m.release_date }}</td>
  <td>{{ m.movie_name }}</td>
  <td>{{ m.language }}</td>
  <td>{{ m.country or '' }}</td>
  <td><span class="bar"><span class="fill" style="width:{{ (m.filled*100//m.total) if m.total else 0 }}%"></span></span>
      <span class="badge">{{ m.filled }}/{{ m.total }}</span></td>
  <td><a href="{{ url_for('edit_movie', movie_name=m.movie_name, release_date=m.release_date, language=m.language, from_q=q, from_language=language, from_year=year, from_incomplete=('1' if incomplete else ''), from_page=page) }}">Edit</a></td>
</tr>
{% endfor %}
</table>
<div class="pager">
  {% if page > 0 %}<a href="?q={{ q }}&language={{ language }}&year={{ year }}&incomplete={{ '1' if incomplete else '' }}&page={{ page-1 }}">&larr; Newer</a>{% endif %}
  <span class="badge">page {{ page+1 }}</span>
  {% if has_next %}<a href="?q={{ q }}&language={{ language }}&year={{ year }}&incomplete={{ '1' if incomplete else '' }}&page={{ page+1 }}">Older &rarr;</a>{% endif %}
</div>
</body></html>
"""

EDIT_TEMPLATE = """
<!doctype html><html><head><title>{{ movie.movie_name }}</title>
<style>
 body { font-family: -apple-system, Helvetica, Arial, sans-serif; margin: 2rem auto; max-width: 860px; color: #1a1a1a; background: #fafafa; }
 h1 { font-size: 1.3rem; margin-bottom: 0; }
 .identity { color: #666; margin-bottom: 1.2rem; }
 fieldset { border: 1px solid #ddd; border-radius: 6px; margin-bottom: 1rem; background: #fff; }
 legend { font-weight: 600; padding: 0 6px; }
 .field { display: flex; align-items: baseline; margin: 8px 0; }
 .field label { width: 320px; flex-shrink: 0; font-size: 0.9rem; }
 .field input { flex: 1; padding: 4px 6px; }
 .actions { margin: 1rem 0; }
 .actions button { padding: 8px 16px; margin-right: 8px; font-size: 0.95rem; }
 table.snap { border-collapse: collapse; width: 100%; margin-bottom: 10px; }
 table.snap th, table.snap td { border-bottom: 1px solid #eee; padding: 4px 6px; font-size: 0.82rem; vertical-align: top; }
 table.snap button { font-size: 0.78rem; padding: 3px 6px; margin: 1px; }
 .backlink { display:block; margin-bottom: 1rem; }
 .flash { background: #eaffea; border: 1px solid #b6e6b6; padding: 6px 10px; border-radius: 4px; margin-bottom: 1rem; }
 .flash.error { background: #ffecec; border-color: #e6b6b6; }
 .badge { font-size: 0.75rem; color: #888; }
 .hint { font-size: 0.8rem; color: #888; margin: 0 0 10px; }
</style></head><body>
<a class="backlink" href="{{ back_url }}">&larr; back to list</a>
{% if saved %}<div class="flash">Saved.</div>{% endif %}
{% if error %}<div class="flash error">{{ error }}</div>{% endif %}
<h1>{{ movie.movie_name }}</h1>
<div class="identity">{% if movie.id %}TMDB/IMDb id {{ movie.id }}{% endif %}</div>

<form method="post" action="{{ url_for('save_movie') }}">
  <input type="hidden" name="orig_movie_name" value="{{ movie.movie_name }}">
  <input type="hidden" name="orig_release_date" value="{{ movie.release_date }}">
  <input type="hidden" name="orig_language" value="{{ movie.language }}">
  <input type="hidden" name="filter_language" value="{{ filter_language }}">
  <input type="hidden" name="filter_year" value="{{ filter_year }}">

  <fieldset><legend>Identity</legend>
    <div class="field">
      <label for="movie_name">Movie name</label>
      <input type="text" id="movie_name" name="movie_name" value="{{ movie.movie_name }}">
    </div>
    <div class="field">
      <label for="release_date">Release date (YYYY-MM-DD)</label>
      <input type="text" id="release_date" name="release_date" value="{{ movie.release_date }}">
    </div>
    <div class="field">
      <label for="language">Language</label>
      <input type="text" id="language" name="language" value="{{ movie.language }}">
    </div>
    <div class="field"><span class="hint">These three together are this row's primary key -- changing any of
      them renames the row rather than creating a new one. If another movie already has the combination you
      type, the save is rejected rather than merging the two.</span></div>
  </fieldset>

  <fieldset><legend>Core</legend>
  {% for col, label, kind in core_fields %}
    <div class="field">
      <label for="{{ col }}">{{ label }}</label>
      <input type="{{ 'text' if kind=='text' else 'number' }}" step="any" id="{{ col }}" name="{{ col }}" value="{{ movie[col] if movie[col] is not none else '' }}">
    </div>
  {% endfor %}
  </fieldset>

  <fieldset><legend>Marketing timing &amp; telemetry</legend>
  {% for col, label, kind in marketing_fields %}
    <div class="field">
      <label for="{{ col }}">{{ label }}</label>
      <input type="{{ 'text' if kind=='text' else 'number' }}" step="any" id="{{ col }}" name="{{ col }}" value="{{ movie[col] if movie[col] is not none else '' }}">
    </div>
  {% endfor %}
  <div class="hint">Days-to-release fields (trailer_days_to_release / teaser_days_to_release /
    song_days_to_release) are recomputed automatically from the dates above when both this movie's release date
    and the marketing-item date are full YYYY-MM-DD -- don't enter them directly.</div>
  </fieldset>

  <div class="actions">
    <button type="submit" name="action" value="save">Save</button>
    <button type="submit" name="action" value="save_next">Save &amp; go to next (older)</button>
  </div>
</form>

<fieldset><legend>Video snapshot matches (movie_video_snapshots)</legend>
<div class="hint">Leakage-free trailer/teaser YouTube view counts reconstructed via the Wayback Machine at two
  checkpoints -- 7 days before release, and on release day itself -- unlike a live YouTube API call, which only
  ever returns today's cumulative (post-release-inflated) count. Matched here by movie-name similarity, not an
  exact join, since this table's movie_name/release_date don't always match this row's exactly (diacritics,
  TMDB-vs-crawler release-date drift) -- check the name/date/similarity shown before applying a match. "Apply"
  only fills the form fields above; it does not save anything until you click Save.</div>
{% if snapshots %}
<table class="snap">
<tr><th>Type</th><th>YouTube video</th><th>Matched snapshot row</th><th>Published</th><th>Views 7d before release</th><th>Views on release</th><th></th></tr>
{% for c in snapshots %}
<tr>
  <td>{{ c.video_type }}</td>
  <td><a href="https://www.youtube.com/watch?v={{ c.youtube_video_id }}" target="_blank" rel="noopener">{{ c.youtube_video_id }}</a></td>
  <td>{{ c.movie_name }} ({{ c.release_date }}, {{ c.language }})<br>
      <span class="badge">similarity {{ '%.2f'|format(c.sim) }}{% if c.date_diff_days is not none %}, {{ c.date_diff_days }}d from this row's release date{% endif %}</span></td>
  <td>{{ c.published_at8 or '' }}</td>
  <td>{{ c.views_7d_before_release if c.views_7d_before_release is not none else '—' }}</td>
  <td>{{ c.views_on_release if c.views_on_release is not none else '—' }}</td>
  <td>
    {% set date_field = 'trailer_release_date' if c.video_type == 'trailer' else ('teaser_release_date' if c.video_type == 'teaser' else none) %}
    {% set views_field_on_release = 'trailer_views' if c.video_type == 'trailer' else ('teaser_views' if c.video_type == 'teaser' else none) %}
    {% set views_field_7d = 'trailer_views_7d_prior_to_release' if c.video_type == 'trailer' else ('teaser_views_7d_prior_to_release' if c.video_type == 'teaser' else none) %}
    {% if date_field and views_field_on_release and views_field_7d %}
      {% if c.views_7d_before_release is not none %}
      <button type="button" onclick='applySnapshot({{ date_field|tojson }}, {{ c.published_at8|tojson }}, {{ views_field_7d|tojson }}, {{ c.views_7d_str|tojson }})'>Use 7d-before</button>
      {% endif %}
      {% if c.views_on_release is not none %}
      <button type="button" onclick='applySnapshot({{ date_field|tojson }}, {{ c.published_at8|tojson }}, {{ views_field_on_release|tojson }}, {{ c.views_on_release_str|tojson }})'>Use on-release</button>
      {% endif %}
    {% endif %}
  </td>
</tr>
{% endfor %}
</table>
{% else %}
<p class="hint">No plausible movie_video_snapshots match found for this movie.</p>
{% endif %}
</fieldset>

<script>
function applySnapshot(dateFieldId, dateValue, viewsFieldId, viewsValue) {
  if (dateValue) { document.getElementById(dateFieldId).value = dateValue; }
  if (viewsValue !== '') { document.getElementById(viewsFieldId).value = viewsValue; }
}
</script>
</body></html>
"""


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.route("/")
def list_movies():
    q = request.args.get("q", "").strip()
    incomplete = request.args.get("incomplete") == "1"
    language = request.args.get("language", "").strip()
    year = request.args.get("year", "").strip()
    page = max(0, int(request.args.get("page", 0) or 0))

    where = []
    params: list = []
    if q:
        where.append("movie_name ILIKE %s")
        params.append(f"%{q}%")
    if language:
        where.append("LOWER(language) = LOWER(%s)")
        params.append(language)
    if year:
        where.append("LEFT(release_date, 4) = %s")
        params.append(year)
    if incomplete:
        where.append("(" + " OR ".join(
            f"{c} IS NULL OR {c} = 0" if c in ("runtime", "rating_10", "budget", "revenue")
            else f"({c} IS NULL OR {c} = '')"
            for c in COMPLETENESS_COLS
        ) + ")")
    where_sql = ("WHERE " + " AND ".join(where)) if where else ""

    conn = get_conn()
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(f"SELECT DISTINCT language, LOWER(language) AS lang_sort FROM {TABLE} ORDER BY lang_sort")
            languages = [r["language"] for r in cur.fetchall()]

            cur.execute(
                f"SELECT DISTINCT LEFT(release_date, 4) AS yr FROM {TABLE} "
                f"WHERE release_date ~ '^[0-9]{{4}}-' ORDER BY yr DESC"
            )
            years = [r["yr"] for r in cur.fetchall()]

            cur.execute(f"SELECT count(*) AS n FROM {TABLE} {where_sql}", params)
            total = cur.fetchone()["n"]

            cur.execute(
                f"""SELECT movie_name, release_date, language, country, genre, directors,
                           runtime, rating_10, cbfc_rating, budget, revenue, production_companies, id
                    FROM {TABLE} {where_sql}
                    ORDER BY release_date DESC, movie_name ASC, language ASC
                    LIMIT %s OFFSET %s""",
                params + [PAGE_SIZE, page * PAGE_SIZE],
            )
            rows = cur.fetchall()

            movies = []
            for r in rows:
                filled, tracked_total = movie_completeness(r)
                movies.append({**r, "filled": filled, "total": tracked_total})
    finally:
        conn.close()

    return render_template_string(
        LIST_TEMPLATE, movies=movies, total=total, q=q, incomplete=incomplete,
        language=language, languages=languages, year=year, years=years,
        page=page, has_next=(page + 1) * PAGE_SIZE < total,
    )


@app.route("/movie")
def edit_movie():
    movie_name = request.args.get("movie_name", "")
    release_date = request.args.get("release_date", "")
    language = request.args.get("language", "")
    saved = request.args.get("saved") == "1"
    error = request.args.get("error")

    conn = get_conn()
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                f"SELECT * FROM {TABLE} WHERE movie_name = %s AND release_date = %s AND language = %s",
                (movie_name, release_date, language),
            )
            movie = cur.fetchone()
            if movie is None:
                return f"No movie found for {movie_name!r} / {release_date!r} / {language!r}", 404

        snapshots = fetch_snapshot_candidates(conn, movie_name, release_date)
    finally:
        conn.close()

    filter_language = request.args.get("from_language", "")
    filter_year = request.args.get("from_year", "")
    back_url = url_for("list_movies", q=request.args.get("from_q", ""),
                        language=filter_language, year=filter_year,
                        incomplete=request.args.get("from_incomplete", ""),
                        page=request.args.get("from_page", 0))
    return render_template_string(
        EDIT_TEMPLATE, movie=movie, snapshots=snapshots,
        core_fields=CORE_FIELDS, marketing_fields=MARKETING_FIELDS,
        saved=saved, error=error, back_url=back_url,
        filter_language=filter_language, filter_year=filter_year)


@app.route("/movie/save", methods=["POST"])
def save_movie():
    f = request.form
    orig_movie_name = f["orig_movie_name"]
    orig_release_date = f["orig_release_date"]
    orig_language = f["orig_language"]
    filter_language = f.get("filter_language", "").strip()
    filter_year = f.get("filter_year", "").strip()

    movie_name = f.get("movie_name", "").strip()
    release_date = f.get("release_date", "").strip()
    language = f.get("language", "").strip()
    if not movie_name or not release_date or not language:
        return redirect(url_for("edit_movie", movie_name=orig_movie_name, release_date=orig_release_date,
                                 language=orig_language, from_language=filter_language, from_year=filter_year,
                                 error="Movie name, release date, and language can't be blank."))

    identity_changed = (movie_name, release_date, language) != (orig_movie_name, orig_release_date, orig_language)

    updates: dict = {"movie_name": movie_name, "release_date": release_date, "language": language}
    for c in TEXT_COLS:
        v = f.get(c, "").strip()
        updates[c] = v if v else None
    for c in NUM_COLS:
        updates[c] = parse_num(f.get(c))
    # remake_of is NOT NULL DEFAULT 'NOT_REMAKE' -- a blank field means "not a
    # remake", not "unknown", so it maps back to the sentinel rather than NULL.
    updates["remake_of"] = f.get("remake_of", "").strip() or "NOT_REMAKE"

    updates["trailer_days_to_release"] = days_between(release_date, updates["trailer_release_date"])
    updates["teaser_days_to_release"] = days_between(release_date, updates["teaser_release_date"])
    updates["song_days_to_release"] = days_between(release_date, updates["first_song_release_date"])

    set_clause = ", ".join(f'"{c}" = %s' for c in updates)
    conn = get_conn()
    try:
        if identity_changed:
            # (movie_name, release_date, language) is the primary key -- renaming
            # into a combination that already belongs to a different row would
            # either violate the PK constraint or silently merge two movies'
            # data. Check first and reject with a clear message instead.
            with conn.cursor() as cur:
                cur.execute(
                    f'SELECT 1 FROM {TABLE} WHERE movie_name = %s AND release_date = %s AND language = %s',
                    (movie_name, release_date, language),
                )
                if cur.fetchone() is not None:
                    return redirect(url_for(
                        "edit_movie", movie_name=orig_movie_name, release_date=orig_release_date,
                        language=orig_language, from_language=filter_language, from_year=filter_year,
                        error=f"Another movie already exists at ({movie_name!r}, {release_date!r}, "
                              f"{language!r}) -- pick a combination that isn't already in use."))

        with conn.cursor() as cur:
            cur.execute(
                f'UPDATE {TABLE} SET {set_clause} '
                f'WHERE movie_name = %s AND release_date = %s AND language = %s',
                list(updates.values()) + [orig_movie_name, orig_release_date, orig_language],
            )
        conn.commit()

        if f.get("action") == "save_next":
            next_where = ["(release_date < %(rd)s OR (release_date = %(rd)s AND movie_name > %(mn)s) "
                          "OR (release_date = %(rd)s AND movie_name = %(mn)s AND language > %(lg)s))"]
            next_params = {"rd": release_date, "mn": movie_name, "lg": language}
            if filter_language:
                next_where.append("LOWER(language) = LOWER(%(fl)s)")
                next_params["fl"] = filter_language
            if filter_year:
                next_where.append("LEFT(release_date, 4) = %(fy)s")
                next_params["fy"] = filter_year
            with conn.cursor() as cur:
                cur.execute(
                    f"""SELECT movie_name, release_date, language FROM {TABLE}
                        WHERE {" AND ".join(next_where)}
                        ORDER BY release_date DESC, movie_name ASC, language ASC
                        LIMIT 1""",
                    next_params,
                )
                nxt = cur.fetchone()
            if nxt:
                return redirect(url_for("edit_movie", movie_name=nxt[0], release_date=nxt[1],
                                         language=nxt[2], saved=1, from_language=filter_language,
                                         from_year=filter_year))
    finally:
        conn.close()

    return redirect(url_for("edit_movie", movie_name=movie_name, release_date=release_date,
                             language=language, saved=1, from_language=filter_language, from_year=filter_year))


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--http-host", default="127.0.0.1")
    p.add_argument("--http-port", type=int, default=3033)
    p.add_argument("--page-size", type=int, default=PAGE_SIZE)
    return p.parse_args()


def main() -> None:
    global DB_ARGS, PAGE_SIZE
    args = parse_args()
    DB_ARGS = dict(host=args.db_host, port=args.db_port, dbname=args.db_name,
                    user=args.db_user, password=args.db_password)
    PAGE_SIZE = args.page_size

    conn = get_conn()
    try:
        ensure_schema(conn)
    finally:
        conn.close()

    print(f"India movie data-entry UI on http://{args.http_host}:{args.http_port}/ "
          f"(db: {args.db_user}@{args.db_host}:{args.db_port}/{args.db_name})")
    app.run(host=args.http_host, port=args.http_port, debug=False)


if __name__ == "__main__":
    main()
