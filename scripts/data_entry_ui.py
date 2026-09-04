#!/usr/bin/env python3
"""
data_entry_ui.py -- local web UI for filling in the movie-revenue model's
data gaps directly against `movies_data_collection`/`actors_data_collection`.

Every field on the edit page is one `movie_revenue_impact_model.py` actually
reads (see that script's WANTED_MOVIE_COLUMNS / FACTOR_DEFS / DERIVED_FACTOR_FNS
for the authoritative list) -- this UI doesn't invent new columns beyond
`cbfc_rating` (added alongside this script, see compute_cbfc_rating_raw).

Ordered latest-to-oldest by `release_date` by default, per the stated
workflow ("start filling in the data from the latest to the oldest").
`trailer_days_to_release`/`teaser_days_to_release`/`song_days_to_release` are
never entered directly -- they're recomputed on save from the two dates
(release_date minus trailer/teaser/first_song release date), so they can
never drift out of sync with what you actually typed in.

Scoped to India-market rows only (an India-only model is the current goal --
see chat), using the exact same predicate `movie_revenue_impact_model.py`'s
`--market india` flag uses (`is_india_market_row`: an Indian-language value,
OR `country == 'India'`) -- not a separately-invented definition, so what
gets filled in here is exactly the row set the India-only model will
actually train on.

Requirements
------------
    pip install flask psycopg2-binary

Usage
-----
    python3 data_entry_ui.py --db-host localhost --db-name aura --db-user mukundv \
        --db-password ... --http-port 3030

Then open http://127.0.0.1:3030/ -- binds to 127.0.0.1 by default (not
0.0.0.0), i.e. local-machine-only unless you pass --http-host 0.0.0.0
yourself.
"""
from __future__ import annotations

import argparse
import os
import sys
from datetime import date, datetime
from typing import Optional

import psycopg2
import psycopg2.extras
from flask import Flask, redirect, render_template_string, request, url_for

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from movie_revenue_impact_model import INDIAN_LANGUAGES, parse_release_date  # noqa: E402

app = Flask(__name__)
DB_ARGS: dict = {}
PAGE_SIZE = 50

# Same predicate as is_india_market_row() in movie_revenue_impact_model.py
# (Indian-language value, OR country == 'India'), expressed as SQL so
# COUNT/ORDER BY/LIMIT all run at the DB level instead of pulling 393k+ rows
# into Python to filter -- built from the shared INDIAN_LANGUAGES set rather
# than a second hand-maintained list, so the two can't drift apart.
INDIA_MARKET_SQL = "(language ~* %s OR country = 'India')"
INDIA_MARKET_PARAM = r"\y(" + "|".join(sorted(INDIAN_LANGUAGES)) + r")\y"

# ---------------------------------------------------------------------------
# Field catalogue -- every one of these is read by movie_revenue_impact_model.py
# today. `factor` names which trained factor (if any) it feeds, so the UI can
# show that instead of leaving the user to guess. Fields marked
# `unwired=True` are real columns the model LOADS but no factor currently
# CONSUMES (verified against DERIVED_FACTOR_FNS/WANTED_MOVIE_COLUMNS) --
# still worth collecting since wiring them in later is a script change, not
# a data-collection one, but flagged here so it's not overstated.
# ---------------------------------------------------------------------------
MOVIE_FIELDS = [
    # (column, label, type, factor/anchor it feeds)
    ("genre", "Genre", "text", "feeds r_concept (baseline anchor)"),
    ("country", "Country", "text", "feeds market detection (India vs. non-India)"),
    ("directors", "Director(s)", "text", "feeds r_director (baseline anchor)"),
    ("production_companies", "Production companies (comma-separated)", "text",
     "feeds joint_production_partnerships"),
    ("runtime_mins", "Runtime (minutes)", "number", "feeds excessive_runtime"),
    ("budget", "Budget (USD)", "number", "feeds ln_budget_effective (baseline anchor) + budget_scale_efficiency"),
    ("revenue", "Revenue (USD)", "number", "the training label -- required for this row to train on at all"),
    ("cbfc_rating", "CBFC rating (U / UA / A)", "text", "feeds cbfc_rating (candidate -- not yet promoted)"),
    ("release_event_type", "Release event type", "text",
     "collected, not yet wired into any factor -- free text for now"),
    ("release_event_name", "Release event name", "text",
     "collected, not yet wired into any factor -- free text for now"),
    ("release_event_detail", "Release event detail", "text",
     "collected, not yet wired into any factor -- free text for now"),
    ("trailer_release_date", "Trailer release date (YYYY-MM-DD)", "text",
     "feeds trailer_teaser_impact via trailer_days_to_release (auto-computed on save)"),
    ("trailer_views", "Trailer views", "number", "feeds trailer_teaser_impact"),
    ("trailer_comments", "Trailer comments", "number",
     "collected, not yet wired into any factor -- free text for now"),
    ("teaser_release_date", "Teaser release date (YYYY-MM-DD)", "text",
     "used only as a marketing-telemetry-present flag today (teaser_days_to_release itself feeds no factor)"),
    ("teaser_views", "Teaser views", "number",
     "collected, not yet wired into any factor -- free text for now"),
    ("teaser_comments", "Teaser comments", "number",
     "collected, not yet wired into any factor -- free text for now"),
    ("first_song_release_date", "First song release date (YYYY-MM-DD)", "text",
     "feeds first_single_timing via song_days_to_release (auto-computed on save)"),
    ("song_views", "Song views", "number",
     "collected, not yet loaded by the model today -- see the chat writeup"),
    ("song_comments", "Song comments", "number",
     "collected, not yet loaded by the model today -- see the chat writeup"),
]

CAST_COLUMNS = ["actor_name", "role_position", "director", "character_name"]


# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

def get_conn():
    return psycopg2.connect(**DB_ARGS)


def parse_num(raw: Optional[str]):
    if raw is None or str(raw).strip() == "":
        return None
    try:
        return float(raw)
    except ValueError:
        return None


def days_between(later_str: Optional[str], earlier_str: Optional[str]) -> Optional[int]:
    """later - earlier, in days, using the model's own parse_release_date (day-level
    dates only -- either side being year-only or unparseable leaves this NULL, same
    as the model's own behavior when it can't resolve a real date)."""
    if not later_str or not earlier_str:
        return None
    later = parse_release_date(later_str)
    earlier = parse_release_date(earlier_str)
    if later is None or earlier is None:
        return None
    return (later - earlier).days


def movie_completeness(row: dict) -> tuple[int, int]:
    tracked = ["genre", "directors", "runtime_mins", "budget", "revenue", "production_companies"]
    filled = sum(1 for c in tracked if row.get(c) not in (None, ""))
    return filled, len(tracked)


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

LIST_TEMPLATE = """
<!doctype html><html><head><title>Indian movie data entry</title>
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
<h1>Indian movie data entry ({{ total }} India-market movies, ordered latest &rarr; oldest)</h1>
<div class="controls">
  <form method="get">
    <input type="text" name="q" placeholder="Search movie name..." value="{{ q }}">
    <label><input type="checkbox" name="incomplete" value="1" {{ 'checked' if incomplete else '' }} onchange="this.form.submit()"> incomplete only</label>
    <button type="submit">Search</button>
  </form>
</div>
<table>
<tr><th>Release date</th><th>Movie</th><th>Language</th><th>Country</th><th>Completeness</th><th>Cast rows</th><th></th></tr>
{% for m in movies %}
<tr>
  <td>{{ m.release_date }}</td>
  <td>{{ m.movie_name }}</td>
  <td>{{ m.language }}</td>
  <td>{{ m.country or '' }}</td>
  <td><span class="bar"><span class="fill" style="width:{{ (m.filled*100//m.total) if m.total else 0 }}%"></span></span>
      <span class="badge">{{ m.filled }}/{{ m.total }}</span></td>
  <td>{{ m.cast_count }}</td>
  <td><a href="{{ url_for('edit_movie', movie_name=m.movie_name, release_date=m.release_date, language=m.language, from_q=q, from_incomplete=('1' if incomplete else ''), from_page=page) }}">Edit</a></td>
</tr>
{% endfor %}
</table>
<div class="pager">
  {% if page > 0 %}<a href="?q={{ q }}&incomplete={{ '1' if incomplete else '' }}&page={{ page-1 }}">&larr; Newer</a>{% endif %}
  <span class="badge">page {{ page+1 }}</span>
  {% if has_next %}<a href="?q={{ q }}&incomplete={{ '1' if incomplete else '' }}&page={{ page+1 }}">Older &rarr;</a>{% endif %}
</div>
</body></html>
"""

EDIT_TEMPLATE = """
<!doctype html><html><head><title>{{ movie.movie_name }}</title>
<style>
 body { font-family: -apple-system, Helvetica, Arial, sans-serif; margin: 2rem auto; max-width: 780px; color: #1a1a1a; background: #fafafa; }
 h1 { font-size: 1.3rem; margin-bottom: 0; }
 .identity { color: #666; margin-bottom: 1.2rem; }
 fieldset { border: 1px solid #ddd; border-radius: 6px; margin-bottom: 1rem; background: #fff; }
 legend { font-weight: 600; padding: 0 6px; }
 .field { display: flex; align-items: baseline; margin: 8px 0; }
 .field label { width: 320px; flex-shrink: 0; font-size: 0.9rem; }
 .field input { flex: 1; padding: 4px 6px; }
 .hint { font-size: 0.75rem; color: #888; margin-left: 8px; flex-basis: 100%; margin-top: 2px; }
 .actions { margin: 1rem 0; }
 .actions button { padding: 8px 16px; margin-right: 8px; font-size: 0.95rem; }
 table.cast { border-collapse: collapse; width: 100%; margin-bottom: 10px; }
 table.cast th, table.cast td { border-bottom: 1px solid #eee; padding: 4px 6px; font-size: 0.85rem; }
 table.cast input { width: 100%; padding: 3px; }
 .backlink { display:block; margin-bottom: 1rem; }
 .flash { background: #eaffea; border: 1px solid #b6e6b6; padding: 6px 10px; border-radius: 4px; margin-bottom: 1rem; }
</style></head><body>
<a class="backlink" href="{{ back_url }}">&larr; back to list</a>
{% if saved %}<div class="flash">Saved.</div>{% endif %}
<h1>{{ movie.movie_name }}</h1>
<div class="identity">{{ movie.release_date }} &middot; {{ movie.language }}{% if movie.id %} &middot; id {{ movie.id }}{% endif %}</div>

<form method="post" action="{{ url_for('save_movie') }}">
  <input type="hidden" name="movie_name" value="{{ movie.movie_name }}">
  <input type="hidden" name="release_date" value="{{ movie.release_date }}">
  <input type="hidden" name="language" value="{{ movie.language }}">

  <fieldset><legend>Core</legend>
  {% for col, label, kind, hint in fields[:9] %}
    <div class="field">
      <label for="{{ col }}">{{ label }}</label>
      <input type="{{ 'text' if kind=='text' else 'number' }}" step="any" id="{{ col }}" name="{{ col }}" value="{{ movie[col] if movie[col] is not none else '' }}">
      <span class="hint">{{ hint }}</span>
    </div>
  {% endfor %}
  </fieldset>

  <fieldset><legend>Marketing timing &amp; telemetry</legend>
  {% for col, label, kind, hint in fields[9:] %}
    <div class="field">
      <label for="{{ col }}">{{ label }}</label>
      <input type="{{ 'text' if kind=='text' else 'number' }}" step="any" id="{{ col }}" name="{{ col }}" value="{{ movie[col] if movie[col] is not none else '' }}">
      <span class="hint">{{ hint }}</span>
    </div>
  {% endfor %}
  <div class="field"><span class="hint">Days-to-release fields (trailer_days_to_release / teaser_days_to_release /
    song_days_to_release) are recomputed automatically from the dates above when both this movie's release date and
    the marketing-item date are full YYYY-MM-DD -- don't enter them directly.</span></div>
  </fieldset>

  <div class="actions">
    <button type="submit" name="action" value="save">Save</button>
    <button type="submit" name="action" value="save_next">Save &amp; go to next (older)</button>
  </div>
</form>

<fieldset><legend>Cast / crew ({{ cast|length }} row(s)) -- drives lead/director track-record factors</legend>
<table class="cast">
<tr><th>Actor</th><th>Role position (1 = lead)</th><th>Director</th><th>Character</th><th></th></tr>
{% for c in cast %}
<tr>
  <form method="post" action="{{ url_for('delete_cast') }}">
  <td>{{ c.actor_name }}</td>
  <td>{{ c.role_position if c.role_position is not none else '' }}</td>
  <td>{{ c.director or '' }}</td>
  <td>{{ c.character_name or '' }}</td>
  <td>
    <input type="hidden" name="movie_name" value="{{ movie.movie_name }}">
    <input type="hidden" name="release_date" value="{{ movie.release_date }}">
    <input type="hidden" name="language" value="{{ movie.language }}">
    <input type="hidden" name="actor_name" value="{{ c.actor_name }}">
    <button type="submit" onclick="return confirm('Remove this cast row?')">Remove</button>
  </td>
  </form>
</tr>
{% endfor %}
</table>
<form method="post" action="{{ url_for('add_cast') }}">
  <input type="hidden" name="movie_name" value="{{ movie.movie_name }}">
  <input type="hidden" name="release_date" value="{{ movie.release_date }}">
  <input type="hidden" name="language" value="{{ movie.language }}">
  <div class="field"><label>Actor name</label><input type="text" name="actor_name" required></div>
  <div class="field"><label>Role position (1 = lead)</label><input type="number" name="role_position"></div>
  <div class="field"><label>Director</label><input type="text" name="director" value="{{ movie.directors or '' }}"></div>
  <div class="field"><label>Character name (optional)</label><input type="text" name="character_name"></div>
  <div class="actions"><button type="submit">Add cast row</button></div>
</form>
</fieldset>

</body></html>
"""


@app.route("/")
def list_movies():
    q = request.args.get("q", "").strip()
    incomplete = request.args.get("incomplete") == "1"
    page = max(0, int(request.args.get("page", 0) or 0))

    where = [INDIA_MARKET_SQL]
    params: list = [INDIA_MARKET_PARAM]
    if q:
        where.append("movie_name ILIKE %s")
        params.append(f"%{q}%")
    if incomplete:
        where.append("(budget IS NULL OR revenue IS NULL OR genre IS NULL OR genre = '' "
                      "OR directors IS NULL OR directors = '')")
    where_sql = ("WHERE " + " AND ".join(where)) if where else ""

    conn = get_conn()
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(f"SELECT count(*) AS n FROM movies_data_collection {where_sql}", params)
            total = cur.fetchone()["n"]

            cur.execute(
                f"""SELECT movie_name, release_date, language, country, genre, directors,
                           runtime_mins, budget, revenue, production_companies, id
                    FROM movies_data_collection {where_sql}
                    ORDER BY release_date DESC, movie_name ASC, language ASC
                    LIMIT %s OFFSET %s""",
                params + [PAGE_SIZE, page * PAGE_SIZE],
            )
            rows = cur.fetchall()

            movies = []
            for r in rows:
                filled, tracked_total = movie_completeness(r)
                cur.execute(
                    "SELECT count(*) AS n FROM actors_data_collection WHERE movie_name = %s AND release_date = %s",
                    (r["movie_name"], r["release_date"]),
                )
                cast_count = cur.fetchone()["n"]
                movies.append({**r, "filled": filled, "total": tracked_total, "cast_count": cast_count})
    finally:
        conn.close()

    return render_template_string(
        LIST_TEMPLATE, movies=movies, total=total, q=q, incomplete=incomplete,
        page=page, has_next=(page + 1) * PAGE_SIZE < total,
    )


@app.route("/movie")
def edit_movie():
    movie_name = request.args.get("movie_name", "")
    release_date = request.args.get("release_date", "")
    language = request.args.get("language", "")
    saved = request.args.get("saved") == "1"

    conn = get_conn()
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                "SELECT * FROM movies_data_collection WHERE movie_name = %s AND release_date = %s AND language = %s",
                (movie_name, release_date, language),
            )
            movie = cur.fetchone()
            if movie is None:
                return f"No movie found for {movie_name!r} / {release_date!r} / {language!r}", 404

            cur.execute(
                "SELECT actor_name, role_position, director, character_name "
                "FROM actors_data_collection WHERE movie_name = %s AND release_date = %s "
                "ORDER BY role_position NULLS LAST, actor_name",
                (movie_name, release_date),
            )
            cast = cur.fetchall()
    finally:
        conn.close()

    back_url = url_for("list_movies", q=request.args.get("from_q", ""),
                        incomplete=request.args.get("from_incomplete", ""),
                        page=request.args.get("from_page", 0))
    return render_template_string(
        EDIT_TEMPLATE, movie=movie, cast=cast, fields=MOVIE_FIELDS, saved=saved, back_url=back_url)


@app.route("/movie/save", methods=["POST"])
def save_movie():
    f = request.form
    movie_name, release_date, language = f["movie_name"], f["release_date"], f["language"]

    text_cols = ["genre", "country", "directors", "production_companies", "cbfc_rating",
                 "release_event_type", "release_event_name", "release_event_detail",
                 "trailer_release_date", "teaser_release_date", "first_song_release_date"]
    num_cols = ["runtime_mins", "budget", "revenue", "trailer_views", "trailer_comments",
                "teaser_views", "teaser_comments", "song_views", "song_comments"]

    updates: dict = {}
    for c in text_cols:
        v = f.get(c, "").strip()
        updates[c] = v if v else None
    for c in num_cols:
        updates[c] = parse_num(f.get(c))

    updates["trailer_days_to_release"] = days_between(release_date, updates["trailer_release_date"])
    updates["teaser_days_to_release"] = days_between(release_date, updates["teaser_release_date"])
    updates["song_days_to_release"] = days_between(release_date, updates["first_song_release_date"])

    set_clause = ", ".join(f"{c} = %s" for c in updates)
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"UPDATE movies_data_collection SET {set_clause} "
                f"WHERE movie_name = %s AND release_date = %s AND language = %s",
                list(updates.values()) + [movie_name, release_date, language],
            )
        conn.commit()

        if f.get("action") == "save_next":
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT movie_name, release_date, language FROM movies_data_collection
                       WHERE release_date < %(rd)s
                          OR (release_date = %(rd)s AND movie_name > %(mn)s)
                          OR (release_date = %(rd)s AND movie_name = %(mn)s AND language > %(lg)s)
                       ORDER BY release_date DESC, movie_name ASC, language ASC
                       LIMIT 1""",
                    {"rd": release_date, "mn": movie_name, "lg": language},
                )
                nxt = cur.fetchone()
            if nxt:
                return redirect(url_for("edit_movie", movie_name=nxt[0], release_date=nxt[1],
                                         language=nxt[2], saved=1))
    finally:
        conn.close()

    return redirect(url_for("edit_movie", movie_name=movie_name, release_date=release_date,
                             language=language, saved=1))


@app.route("/movie/cast/add", methods=["POST"])
def add_cast():
    f = request.form
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO actors_data_collection
                       (actor_name, movie_name, release_date, language, director, role_position, character_name)
                   VALUES (%s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (actor_name, movie_name, release_date) DO UPDATE SET
                       language = EXCLUDED.language, director = EXCLUDED.director,
                       role_position = EXCLUDED.role_position, character_name = EXCLUDED.character_name""",
                (f["actor_name"].strip(), f["movie_name"], f["release_date"], f.get("language") or None,
                 f.get("director", "").strip() or None,
                 int(f["role_position"]) if f.get("role_position", "").strip() else None,
                 f.get("character_name", "").strip() or None),
            )
        conn.commit()
    finally:
        conn.close()
    return redirect(url_for("edit_movie", movie_name=f["movie_name"], release_date=f["release_date"],
                             language=request.args.get("language", "")))


@app.route("/movie/cast/delete", methods=["POST"])
def delete_cast():
    f = request.form
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM actors_data_collection WHERE actor_name = %s AND movie_name = %s AND release_date = %s",
                (f["actor_name"], f["movie_name"], f["release_date"]),
            )
        conn.commit()
    finally:
        conn.close()
    return redirect(url_for("edit_movie", movie_name=f["movie_name"], release_date=f["release_date"],
                             language=f.get("language", "")))


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-host", default=os.environ.get("MOVIE_DB_HOST", "localhost"))
    p.add_argument("--db-port", type=int, default=int(os.environ.get("MOVIE_DB_PORT", 5432)))
    p.add_argument("--db-name", default=os.environ.get("MOVIE_DB_NAME", "aura"))
    p.add_argument("--db-user", default=os.environ.get("MOVIE_DB_USER", os.environ.get("USER", "postgres")))
    p.add_argument("--db-password", default=os.environ.get("MOVIE_DB_PASSWORD", ""))
    p.add_argument("--http-host", default="127.0.0.1")
    p.add_argument("--http-port", type=int, default=3030)
    p.add_argument("--page-size", type=int, default=PAGE_SIZE)
    return p.parse_args()


def main() -> None:
    global DB_ARGS, PAGE_SIZE
    args = parse_args()
    DB_ARGS = dict(host=args.db_host, port=args.db_port, dbname=args.db_name,
                    user=args.db_user, password=args.db_password)
    PAGE_SIZE = args.page_size
    print(f"Movie data-entry UI on http://{args.http_host}:{args.http_port}/ "
          f"(db: {args.db_user}@{args.db_host}:{args.db_port}/{args.db_name})")
    app.run(host=args.http_host, port=args.http_port, debug=False)


if __name__ == "__main__":
    main()
