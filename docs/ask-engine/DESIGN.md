# Ask Engine — Design

The **Ask** engine is AuraMath's natural-language → database + mathematician feature. A caller
supplies a question in plain English plus a connection to the database they want answered against;
the engine introspects that database, drafts SQL with an LLM, validates and executes it read-only,
optionally runs mathematical post-processing, and composes a natural-language answer.

It lives entirely under the sub-package `com.lit.fire.flame.nlq` and is independent of AuraMath's
existing controllers and `DataSourceConfig`.

## Pipeline

```
question + target connection + skip-list
        │
        ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  1. CONNECT  │──▶│ 2. INTROSPECT│──▶│  3. NL → SQL │──▶│  4. VALIDATE │
│  per-request │   │ schema, then │   │  LLM drafts  │   │ read-only +  │
│  isolated DS │   │ apply skip   │   │  SQL from    │   │ skip-list    │
│              │   │ list filter  │   │  schema+Q    │   │ re-enforced  │
└──────────────┘   └──────────────┘   └──────────────┘   └──────┬───────┘
                                                                 │
        ┌────────────────────────────────────────────────────────┘
        ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  5. EXECUTE  │──▶│   6. MATH    │──▶│  7. ANSWER   │
│ row cap +    │   │ optional     │   │ LLM composes │
│ query timeout│   │ stats/formula│   │ NL response  │
└──────────────┘   └──────────────┘   └──────────────┘
```

1. **Connect** (`nlq.connection`) — build a target datasource from the per-request connection
   details (Postgres, SQLite, or MySQL). Apply the connection timeout and a read-only flag. This
   datasource is never the AuraMath application datasource.
2. **Introspect** (`nlq.schema`) — read tables, columns, types, and relationships, then **remove
   anything on the skip-list** before rendering a compact schema description.
3. **NL → SQL** (`nlq.sql` + `nlq.llm`) — prompt the LLM with the question and the filtered schema;
   it returns a single SQL statement.
4. **Validate** (`nlq.sql`) — confirm the statement is a single read-only query
   (`SELECT`/`WITH` only) and references no skip-listed table/column. Reject otherwise.
5. **Execute** (`nlq.sql`) — run against the isolated target connection under the configured row
   cap (`maxRows`) and query timeout (`queryTimeoutSeconds`).
6. **Math** (`nlq.math`) — optional numeric/statistical post-processing of the result set
   (commons-math3, ad-hoc formulas via exp4j).
7. **Answer** (`nlq.llm`/`nlq.api`) — compose a natural-language answer, returned by the REST
   layer in `nlq.api`.

## Guarantees

- **Read-only.** Only `SELECT`/`WITH` statements are ever generated or executed. The validator
  rejects any other statement form before execution; connections are opened read-only as defense
  in depth.
- **Isolation.** Target database connections are supplied per-request and constructed in
  `nlq.connection`, fully separate from AuraMath's own `DataSourceConfig` datasource. The engine
  never queries the application's own database on a caller's behalf.
- **Skip-list & masking.** A request may name tables/columns to skip; the server adds its own
  defaults, masked columns, and auto-skip name patterns. **Skipped** objects are excluded from the
  schema the model sees (step 2) **and** re-enforced at validation/execution (steps 4–5) and dropped
  from output (step 8); **masked** columns stay visible for aggregation but their raw values are
  never projected (rejected at step 4) or returned (redacted at step 8). See
  [Sensitive-data hardening (F9)](#sensitive-data-hardening-f9) for the full model and precedence.
- **Pluggable LLM.** The model layer sits behind an `LlmClient` interface in `nlq.llm`; the Claude
  implementation is first. The active provider is selected via `aura.ask.llm-provider`.

## Connection model (F1)

Target connections are built **per request** by `nlq.connection.DynamicConnectionFactory` and are
never derived from, nor allowed to fall back to, AuraMath's own `DataSourceConfig` datasource. The
factory:

- validates the JDBC URL (see whitelist/denylist below) and resolves the driver;
- explicitly loads the driver class via `Class.forName` — one of the three supported drivers only;
- applies a login timeout from `aura.ask.connection-timeout-seconds`
  (`DriverManager.setLoginTimeout`);
- opens a brand-new `java.sql.Connection` with **read-only intent** and leaves autocommit at the
  driver default;
- returns the live connection to the caller, who **owns and must close it** (it is `AutoCloseable`;
  use try-with-resources). The factory keeps no pool and no reference — connections are short-lived.

Read-only is applied in a driver-appropriate way: Postgres and MySQL accept `setReadOnly(true)` on
the live connection; SQLite (Xerial) rejects that after connect, so the read-only **open mode** is
baked into the connection properties before connecting. If a connection cannot be made read-only it
is closed and the open fails — fail closed.

### Supported drivers

| Alias        | URL scheme prefix    | Driver class                  |
|--------------|----------------------|-------------------------------|
| `postgresql` | `jdbc:postgresql:`   | `org.postgresql.Driver`       |
| `sqlite`     | `jdbc:sqlite:`       | `org.sqlite.JDBC`             |
| `mysql`      | `jdbc:mysql:`        | `com.mysql.cj.jdbc.Driver`    |

The request's `driver` field is optional; when omitted it is auto-detected from the URL scheme.
When supplied it must agree with the scheme, or the request is rejected.

### JDBC-URL whitelist / denylist

`nlq.connection.JdbcUrlValidator` enforces two gates, both fail-closed:

1. **Scheme whitelist** — the URL must start with one of the three prefixes above. Anything else
   (`jdbc:h2:`, `jdbc:oracle:`, …) is rejected.
2. **Parameter denylist** — the URL must not contain any of these case-insensitive substrings,
   which enable local-file loading, custom socket factories, init statements, or gadget
   deserialization on one or more drivers:
   `allowLoadLocalInfile`, `socketFactory`, `init`, `autoDeserialize`, `allowUrlInLocalInfile`.

### Connection-test endpoint

`POST /api/ask/test-connection` (in `nlq.api.AskConnectionController`) opens a connection from a
`ConnectionRequest`, runs a trivial `SELECT 1` probe, and returns
`{connected, databaseProductName, databaseProductVersion, error}`. The password is accepted only in
the request body and **never** appears in the response (the result type has no password field) or in
any log. A rejected scheme/driver/denylisted parameter returns `400`; a reachable-but-failing
connection returns `200` with `connected=false` and the error.

## Schema model (F2)

After a connection is open, `nlq.schema.SchemaIntrospector` walks `java.sql.DatabaseMetaData` to
build an immutable `DatabaseSchema`. **Introspection reads metadata only — never any row data.**

### Model

- `DatabaseSchema` — the model-visible tables that survived filtering, plus the reported
  `productName` and a coarse `dialect` hint (`postgresql` / `sqlite` / `mysql`).
- `TableInfo` — `schema` (nullable for SQLite), `name`, ordered `ColumnInfo` list, primary-key
  column names (in key order), and imported `ForeignKeyInfo` references.
- `ColumnInfo` — `name`, `sqlType` (the `java.sql.Types` code), `typeName` (the driver's native
  type name), and `nullable`.
- `ForeignKeyInfo` — owning `column` → `referencedSchema.referencedTable.referencedColumn`.

The metadata calls used are exactly `getTables` (`TABLE`/`VIEW`), `getColumns`, `getPrimaryKeys`,
and `getImportedKeys`. No `SELECT` is ever issued during introspection.

### System-schema exclusions

Only application (user) tables and views are surfaced. The following are always excluded so the
model never sees engine internals:

- **Postgres** — `pg_catalog`, any `pg_*` schema (e.g. `pg_toast`), and `information_schema`.
- **MySQL** — `mysql`, `sys`, `performance_schema`, and `information_schema`.
- **SQLite** — internal `sqlite_*` tables (e.g. `sqlite_sequence`, `sqlite_master`).

### Skip-list semantics

`nlq.schema.SkipList` carries the **effective** skip-list, which is the **union** of:

1. the server-side `aura.ask.default-skip-tables` (always applied), and
2. the per-request `skipTables` / `skipColumns` (on the request model).

Matching is **case-insensitive** and **schema-qualified-aware**:

- A **table** entry is either a bare name (`users`, matching that table in *any* schema) or
  schema-qualified (`public.users`, matching *only* that schema's table).
- A **column** entry is either `table.column` or `schema.table.column`, with the same semantics.

Skipped **tables** are omitted from `DatabaseSchema` entirely; skipped **columns** are removed from
their table's column list (and from any primary/foreign key that referenced them). A table whose
every column is skipped is dropped as well. Because filtering happens during introspection, skipped
objects are structurally absent from the model and cannot reappear downstream.

### Rendering

`nlq.schema.SchemaRenderer` serializes a `DatabaseSchema` into a compact, token-efficient
CREATE-TABLE-like listing (a `dialect` comment, then one `TABLE name ( … )` block per table with
`PK` / `NOT NULL` markers and inline `FK -> ref` pointers). It emits **structure only, no data
rows**, and operates on the already-filtered schema — so a skipped table or column can never appear
in the rendered prompt. A unit test asserts a skipped table name is absent from the rendered output.

## LLM layer (F3)

The model layer sits behind a **provider-neutral** `nlq.llm.LlmClient` so the engine never depends
on a specific vendor's SDK or types. Callers build an `LlmRequest`, call `complete`, and read an
`LlmResponse`; the active implementation is selected in `nlq.config` from `aura.ask.llm-provider`.

### Contract

`LlmClient.complete(LlmRequest) -> LlmResponse` runs one single-turn completion.

- **`LlmRequest`** (immutable; `LlmRequest.builder()`): `systemPrompt` (optional), `userPrompt`
  (required), `jsonSchema` (optional, a JSON Schema object requesting structured output),
  `structuredToolName` (defaults to `structured_output`), `maxTokens`, `temperature` (optional —
  see below), and `modelId` (optional; the client applies its default when unset).
- **`LlmResponse`** (immutable): `text` (concatenated text output; never null, may be empty),
  `structuredJson` (a Gson `JsonObject`, non-null only for a structured request that succeeded),
  `stopReason`, and `inputTokens` / `outputTokens` (`-1` when the provider does not report them).
- **`LlmException`** (checked, typed): carries an `LlmException.Kind` —
  `CONFIGURATION` / `RATE_LIMITED` / `TIMEOUT` / `HTTP_ERROR` / `BAD_RESPONSE` — plus an HTTP status
  for the HTTP cases. Messages are deliberately terse and **never** contain the API key or prompt.

The contract is provider-neutral by design: no Claude/OpenAI-specific type appears on the request or
response, and structured output is requested with a generic JSON Schema rather than any provider's
tool format.

### Claude implementation

`nlq.llm.ClaudeLlmClient` calls the Anthropic **Messages API** (`POST /v1/messages`) directly over
`java.net.http.HttpClient`, serializing and deserializing with Gson — no Anthropic SDK is added.

- **Credentials.** The API key is read once at construction from `secrets.txt` on the classpath
  (key `anthropic.api.key`), the same loading pattern as `DataSourceConfig`. A missing key does
  **not** fail construction — the bean still wires up — and the first `complete` call then fails with
  a clear `CONFIGURATION` error. The key is never logged.
- **Default model.** `claude-opus-4-8` for reasoning-heavy calls, overridable per request via
  `LlmRequest.modelId`.
- **Temperature.** Optional and omitted from the request unless explicitly set, because the default
  model rejects sampling parameters. Set it only when targeting a model that accepts it.
- **Structured output.** When a request carries a JSON Schema, the schema is sent as a single tool's
  `input_schema` and `tool_choice` forces that tool, so the model must return a matching JSON object;
  that object is surfaced on `LlmResponse.structuredJson`.
- **Resilience.** Non-2xx responses, timeouts, and rate limits (429) raise a typed `LlmException`.
  Retryable failures (429, 5xx, transport timeouts) are retried **once** with a bounded backoff that
  honours a `Retry-After` header when present; other 4xx errors are not retried.
- **Logging.** Prompt and schema contents are never logged at `INFO`; only coarse, non-sensitive
  metadata (model id, retry notices at `DEBUG`) is emitted, and never the API key.

### Adding another provider

1. Implement `LlmClient` (e.g. `OpenAiLlmClient`) translating `LlmRequest`/`LlmResponse` to/from the
   new vendor — keep all vendor types inside the implementation.
2. Add a branch in `AskEngineConfiguration.llmClient(...)` for the new `aura.ask.llm-provider` value.
3. No caller changes: the engine depends only on the `LlmClient` interface.

A round-trip is covered by `ClaudeLlmClientTest`, which is skipped unless `ANTHROPIC_API_KEY` is set
in the environment; the no-key configuration-error case always runs offline.

## NL → SQL generation (F4)

`nlq.sql.SqlGenerationService` turns a question + filtered schema into a **single drafted read-only
query**. It renders the schema with `SchemaRenderer` (F2), prompts the model through the
`LlmClient` (F3) in **structured-output mode**, and returns a `SqlGenerationResult`.

`generate(question, DatabaseSchema)` is the contract. The schema's detected
`DatabaseSchema.getDialect()` is passed through to the model so it uses dialect-correct syntax
(`LIMIT` vs `FETCH FIRST`, identifier quoting, date functions).

### Generation contract

`SqlGenerationResult` is one of two shapes:

- **A drafted query** — `sql` is a single `SELECT`/`WITH` statement, with `tablesUsed` (the tables
  it reads), `assumptions` (interpretations the model made, e.g. which column means "signed up"),
  and an optional `confidence` in `[0,1]`.
- **A clarification** — `sql` is `null`, `clarificationNeeded` is `true`, and `clarificationQuestion`
  carries a specific question to put back to the caller.

**The result is a draft, not trusted output.** F4 does not execute anything and does not validate
the SQL — read-only + skip-list validation is F5 and bounded execution is F6. The SQL here is still
untrusted.

### Structured output shape

The model is asked (via the client's structured-output / tool-use mode) to return JSON matching this
schema — required fields `sql`, `tablesUsed`, `assumptions`, `clarificationNeeded`; optional
`confidence` and `clarificationQuestion`:

```json
{
  "sql": "SELECT count(*) AS signups FROM users WHERE created_at >= ... LIMIT 100",
  "tablesUsed": ["users"],
  "assumptions": ["'signed up' maps to users.created_at"],
  "confidence": 0.82,
  "clarificationNeeded": false,
  "clarificationQuestion": ""
}
```

### System-prompt guarantees

The system prompt (a resource template at `src/main/resources/nlq/sql_generation_system.txt`, with
`${dialect}` / `${maxRows}` placeholders) instructs the model to:

- **Read-only** — produce only a single query beginning with `SELECT`/`WITH`; never
  `INSERT`/`UPDATE`/`DELETE`/`MERGE`, DDL, or DCL.
- **Single statement** — never emit multiple statements or any SQL comments.
- **Schema-bounded** — reference only tables/columns present in the rendered schema (which is
  already skip-list-filtered in F2, so skipped objects are not even visible); use exact names, prefer
  explicit column lists, and join only on the schema's `FK -> ...` relationships.
- **Bounded** — always include a row limit `<=` the configured `aura.ask.max-rows`.
- **Ask, don't guess** — if the question cannot be answered from the schema, set
  `clarificationNeeded` and return a question instead of inventing SQL.

### Clarification path and the table-subset pre-check

Two routes lead to a clarification: the model sets `clarificationNeeded` itself, or the service's
**cheap pre-check** finds that `tablesUsed` is *not* a subset of the schema's tables. In the latter
case the drafted SQL is discarded and a clarification naming the unknown table(s) is returned, so the
engine never hands downstream a query that references something the model invented. (A missing/empty
`sql` is also treated as a clarification defensively.) This pre-check is a guard only — F5 still
re-enforces read-only and skip-list constraints on whatever SQL does survive.

`SqlGenerationServiceTest` covers the acceptance case (a small SQLite schema + "how many users
signed up last month" → a single `SELECT … LIMIT` with `users` in `tablesUsed`), the unknown-table
pre-check, a model-driven clarification, and a missing-structured-output error. It uses a capturing
stub `LlmClient`, so it runs offline with no API key.

## Security model (F5)

The LLM is **untrusted**. Its drafted SQL is never executed directly: it must first pass
`nlq.sql.SqlSafetyGuard.validate(sql, SkipList, DatabaseSchema)`, which either returns a normalized,
row-capped, safe SQL string or throws a typed `UnsafeSqlException` carrying a precise
`UnsafeSqlException.Reason`. **The validator — not the LLM, and not the F4 prompt — is the trust
boundary.** The F4 generator's own table-subset pre-check is a convenience guard only; F5 re-derives
every constraint from scratch and is the rule that execution (F6) depends on.

Every rule **fails closed**: anything ambiguous, unparseable, or not provably a single read-only
query is rejected. The guard is deliberate about ordering — the cheap string screens for comments,
statement-chaining, and forbidden tokens run on the **raw** input first, because a real parser
silently discards comments and we must *reject* smuggled input rather than sanitize it. It then
parses with **JSqlParser** (a real SQL parser, not regex) to confirm the statement shape and to
enumerate referenced tables reliably.

Guard rules, in order:

1. **Non-empty** — null/blank input is rejected (`EMPTY`).
2. **No comments** — any `--`, `/*`, or `*/` is rejected (`COMMENT`). SQL comments are the classic
   vehicle for smuggling a second statement or a forbidden token past a downstream parser, so they
   are refused outright rather than stripped.
3. **Single statement** — a single optional trailing `;` is tolerated; any interior `;` is rejected
   (`MULTIPLE_STATEMENTS`). The subsequent parse also accepts only one statement, so chaining is
   caught twice.
4. **Read-only form** — after trimming, the statement must begin with `SELECT` or `WITH`
   (`NOT_READ_ONLY`).
5. **No write/branch keywords** — a word-boundary scan rejects any top-level DML/DDL/DCL keyword:
   `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `GRANT`,
   `REVOKE`, `CALL`, `EXEC`, `EXECUTE`, `COPY`, `ATTACH`, `DETACH`, `PRAGMA`, `VACUUM`, `REINDEX`,
   and the write-form `INTO` (which also covers `SELECT … INTO OUTFILE/DUMPFILE`) (`FORBIDDEN_KEYWORD`).
6. **No exfil/side-effect functions** — a case-insensitive substring screen rejects known
   file/network helpers per dialect: Postgres `pg_read_file`, `pg_read_binary_file`, `pg_ls_dir`,
   `pg_stat_file`, large-object `lo_import`/`lo_export`/`lo_get`/`lo_put`, `dblink`; MySQL
   `load_file`; and `outfile`/`dumpfile` targets (`FORBIDDEN_FUNCTION`). `COPY … TO/FROM`, SQLite
   `ATTACH`, and `PRAGMA` are already covered by rule 5.
7. **Parses to a single SELECT** — JSqlParser must parse the statement; anything it cannot parse is
   rejected (`UNPARSEABLE`), and a parsed statement that is not a `Select` is rejected
   (`NOT_A_QUERY`).
8. **Schema- and skip-list-bounded tables** — every table found by JSqlParser's `TablesNamesFinder`
   must be present in the (already skip-filtered) `DatabaseSchema` (`UNKNOWN_TABLE`) and must not be
   on the effective `SkipList` (`SKIPPED_TABLE`). Because skipped tables were removed from the schema
   during introspection (F2), a reference to one already surfaces as an unknown table; the explicit
   skip-list check is defense in depth. CTE names introduced by a `WITH` clause are recognized as
   valid references so legitimate CTE queries are not rejected.
9. **No skipped columns** — as a conservative backstop, a query that names any skipped column's leaf
   identifier is rejected (`SKIPPED_COLUMN`).
10. **No raw masked columns (F9)** — a masked column may appear in the SELECT projection **only**
    inside a value-combining aggregate (`count`, `sum`, `avg`, `stddev`, `variance`, `corr`, …); a
    bare reference, a scalar expression, or a value-*revealing* aggregate (`min`, `max`,
    `string_agg`/`group_concat`/`array_agg`, `median`, `percentile_*`, `first`/`last`/`mode`) is
    rejected (`MASKED_COLUMN`), as is a star projection (`*` / `t.*`) over any table that owns a masked
    column. References in `WHERE`/`GROUP BY`/`HAVING` are allowed (they surface no value); the result
    redactor is the final backstop on output.
11. **Row cap** — the validated query is bounded by `aura.ask.max-rows`: a missing limit is injected,
    and an existing limit *larger* than the cap is lowered to it; a smaller explicit limit is left
    untouched, and a non-constant limit (parameter/expression) is replaced with the hard cap. The
    guard returns JSqlParser's re-rendered statement, so the executed SQL is exactly what was
    validated.

**Known limitations (intentional, fail-closed).** The string-level screens (rules 2, 5, 6) are
conservative: a forbidden token or comment marker that appears *inside a string literal* will also
be rejected, and the skipped-column screen (rule 9) over-rejects when an allowed table shares a
column name with a skipped one. For an LLM-facing security boundary, over-rejection is the correct
failure direction — a rejected benign query is recoverable; an executed malicious one is not.

`SqlSafetyGuardTest` covers the acceptance set: a benign `SELECT` passes; `SELECT …; DROP TABLE x`,
a query against a skipped table, `SELECT pg_read_file('/etc/passwd')`, and a commented-out chained
statement are each rejected with the expected reason; and a no-limit query comes back with a bounded
`LIMIT`. It runs offline against an in-memory SQLite schema introspected through F2.

## Query execution (F6)

`nlq.sql.QueryExecutionService` is the only place a generated query actually runs. It takes the
per-request, read-only `java.sql.Connection` from F1 and the safe SQL from F5 and returns a typed
`QueryResult` — or throws a typed, sanitized `QueryExecutionException`.

`execute(connection, sql, SkipList, DatabaseSchema)` is the contract. The caller owns and closes the
connection (it is short-lived).

### Execution contract

1. **Defense-in-depth re-validation.** Before touching the connection the service re-runs the SQL
   through `SqlSafetyGuard.validate(sql, skipList, schema)` — the *same* trust boundary F5 applies —
   and executes exactly the normalized, row-capped string the guard returns. What runs is what was
   just re-validated, not whatever the caller passed in. A failure raises
   `QueryExecutionException(UNSAFE_SQL)`.
2. **Read-only assertion.** It then confirms `connection.isReadOnly()`; a connection that does not
   report read-only is refused with `QueryExecutionException(NOT_READ_ONLY)`. The service never flips
   the flag itself — F1 opens the connection read-only and this is a belt-and-suspenders check.
3. **Bounded statement.** The query runs through a `PreparedStatement` with
   `setQueryTimeout(aura.ask.query-timeout-seconds)`, `setMaxRows(aura.ask.max-rows)`, and a
   streaming `setFetchSize(min(maxRows, 1000))` hint so large results are not buffered whole.

### Result model

`QueryResult` is immutable and carries:

- **`columns`** — ordered `QueryResult.Column` (select-list order): the column label (alias-aware),
  the `java.sql.Types` `sqlType` code, and the driver's native `typeName`.
- **`rows`** — `List<Map<String,Object>>`, each row an ordered name→value map keyed by column label.
- **`rowCount`** — number of rows returned (`= rows.size()`).
- **`truncated`** — `true` when the result filled the `maxRows` cap (see below).
- **`executionMillis`** — wall-clock time to execute and read the result set.

### Type mapping

JDBC values are mapped to JSON-friendly Java types as the result set is read:

| JDBC value | Mapped to |
|------------|-----------|
| `NULL` | `null` |
| `String`, `Boolean`, integral/floating `Number` | passed through |
| `BigDecimal` | preserved (Gson serializes it losslessly as a number) |
| `java.sql.Timestamp` | ISO-8601 local date-time string (`toLocalDateTime().toString()`) |
| `java.sql.Date` / `java.sql.Time` | ISO local date / time string |
| `byte[]` / `Blob` | Base64 string |
| `Clob` | its character content as a `String` |
| anything else (Postgres `jsonb`/arrays/`UUID`, enums, …) | stable `toString()` form |

Temporal values use the `toLocalX()` forms deliberately, to render a clean ISO string without
timezone-shift surprises across drivers.

### Truncation and timeout

- **Truncation.** `setMaxRows(maxRows)` caps the driver, and F5 has already injected a matching
  `LIMIT maxRows`, so a result that *fills* the cap is the truncation signal: `truncated` is set when
  `rowCount == maxRows`, meaning more rows may exist than were returned.
- **Timeout.** `setQueryTimeout` bounds execution; when the driver cancels a query the resulting
  `SQLException` is mapped to `QueryExecutionException(TIMEOUT)`. (Postgres/MySQL cancel CPU-bound
  queries this way; SQLite's query timeout only governs lock contention, so a CPU-bound SQLite query
  is not cut off in-process — a driver limitation, not an engine one.)

### Error sanitization

Any driver `SQLException` is wrapped in a `QueryExecutionException` whose message is **sanitized** —
never the raw driver text (which can echo connection details), never a stack trace. The message
carries only a category and, for non-timeout failures, the `SQLState`; the original exception is
retained as the cause for server-side logging only. The typed `QueryExecutionException.Kind`
(`NOT_READ_ONLY` / `UNSAFE_SQL` / `TIMEOUT` / `EXECUTION`) lets callers react without parsing text.

`QueryExecutionServiceTest` covers the acceptance set against a file-backed SQLite database read over
a separate read-only connection: a `SELECT` returns typed rows with correct column metadata, `NULL`
round-trips as `null`, a result over `maxRows` sets `truncated=true` (and one under it does not), a
writable connection is refused (`NOT_READ_ONLY`), a chained statement is refused at execution
(`UNSAFE_SQL`), and a stubbed timed-out statement confirms the configured timeout is applied and
mapped to `TIMEOUT`. It runs offline.

## Mathematician layer (F7)

`nlq.math.AnswerSynthesisService` turns the rows from F6 into a final natural-language answer,
applying any statistics the question calls for. Its contract is
`answer(question, SqlGenerationResult, QueryResult) -> AskAnswer`, where `AskAnswer` carries the NL
`answer`, the `formulasApplied` (each `{name, expression, inputs, result}`), the `computedValues`
(operation name → the figure Java computed), the `sql`, the `assumptions` (from generation plus any
evaluation notes), and a capped `rowsPreview`.

### Plan, then deterministically evaluate

The layer never lets the model do arithmetic — LLMs are unreliable at it. Instead the work is split
into a plan step and a deterministic evaluation step, with a final narration step:

1. **Plan** — the `LlmClient` (structured output) returns a `ComputationPlan`: *which* catalog
   formula(s) apply and over *which* result columns, as a list of named operations — **not** the
   final numbers. A number written by the model here is ignored.
2. **Evaluate** — `nlq.math.FormulaEvaluator` computes every value **in Java**. It uses
   `commons-math3` for statistics (`DescriptiveStatistics`, `SimpleRegression`,
   `PearsonsCorrelation`) and a **restricted** `exp4j` evaluator for ad-hoc arithmetic — only
   numeric operators and a whitelist of functions (`abs`, `sqrt`, `pow`, `min`, `max`, `log`, `exp`,
   `ceil`, `floor`, …); any other `name(` call is rejected, and an `expression` operation may
   reference only earlier operations' results by name, never raw columns.
3. **Narrate** — the `LlmClient` writes a concise answer **given** the question, the deterministic
   `computedValues`, and the applied formulas. The system prompt forbids it from stating any figure
   that is not in `computedValues` or a previewed row cell, so the prose cannot invent numbers.

### Why arithmetic is computed in Java, not by the LLM

The model is good at *choosing* the right formula and the right column from a natural-language
question; it is not reliable at *executing* the arithmetic, and a wrong-but-plausible figure is worse
than no figure. Splitting "decide the recipe" (model) from "do the math" (`commons-math3` / `exp4j`)
makes every number reproducible and auditable: `formulasApplied` records exactly what was computed
and from which inputs, and `computedValues` is the single source the narrative may quote.

### Supported formula catalog

`count`, `sum`, `mean`/`average`, `min`, `max`, `median`, `std_dev`, `variance`, `percentile`
(arg `percentile`/`p`), `weighted_average` (value + weight columns), `growth_rate`, `cagr`
(optional `periods`), `regression_slope` / `regression_intercept` (x + y columns), `correlation`
(Pearson r, x + y columns), and `expression` (ad-hoc arithmetic over earlier results via `exp4j`).
Common aliases (`avg`, `stddev`, `slope`, `pearson`, …) are normalized.

### Guards and short-circuits

Evaluation **never throws on bad data**: an empty column, a non-numeric column, a missing column,
division by zero (e.g. zero weights, a zero base in `growth_rate`/`cagr`), or a non-finite result
causes that one operation to be **skipped** and a note recorded in `assumptions`; other operations
still run. Two whole-question short-circuits avoid fabricated math entirely:

- **Empty result set** — F7 returns a factual *"No data: the query returned no rows…"* answer
  **without calling the model at all**, so an empty result can never become a hallucinated number.
- **Pure lookup** — when the plan sets `lookupOnly` (or yields no operations), the answer is composed
  directly from the rows with no statistics, and the narrative is told there are no computed values.

`FormulaEvaluatorTest` covers the deterministic core offline (exact `mean`, percentile, an
`expression` composing earlier sums, plus the empty / non-numeric / divide-by-zero / disallowed-function
guards). `AnswerSynthesisServiceTest` covers the acceptance case — "what is the average order value"
plans a `mean`, `commons-math3` computes `25`, and the narrative is handed that exact figure — plus
the empty-set "no data" short-circuit (no model call) and the lookup path. Both use a capturing stub
`LlmClient`, so they run with no API key.

## Orchestration & REST API (F8)

`nlq.api.AskOrchestrator` wires F1–F7 into one end-to-end call, and `nlq.api.AskController` exposes it
at **`POST /api/ask`** (a sibling of the F1 `POST /api/ask/test-connection`, both under `/api/ask`).
The request is an `AskRequest` (`connection`, `question`, `skipTables`, `skipColumns`, optional
`model`, optional `maxRows`); the success response is an `AskResponse`.

### End-to-end sequence

```
POST /api/ask  (AskRequest: connection + question + skip-list + optional model/maxRows)
        │
        ▼
  AskController ──[aura.ask.enabled? no → 503]
        │ yes
        ▼
  AskOrchestrator.ask(request)
        │   effective skip-list = default-skip-tables ∪ connection skips ∪ request skips
        │   effective maxRows   = min(request.maxRows ?? ceiling, ceiling)   // clamp, never raise
        │
        ├─▶ 1. open target connection            (F1 DynamicConnectionFactory)   ── timing: connect
        ├─▶ 2. introspect with skip-list          (F2 SchemaIntrospector)         ── timing: introspect
        ├─▶ 3. generate SQL                        (F4 SqlGenerationService)       ── timing: generate
        │        └─ clarificationNeeded? ─── yes ──▶ AskResponse.clarification ──▶ HTTP 400 (+ body)
        ├─▶ 4. validate read-only + skip-list      (F5 SqlSafetyGuard)             ── timing: validate
        ├─▶ 5. execute bounded + timed             (F6 QueryExecutionService)      ── timing: execute
        ├─▶ 6. synthesize answer (plan→eval→narrate)(F7 AnswerSynthesisService)    ── timing: synthesize
        │
        └─[finally] close the target connection (always)
        ▼
  AskResponse (answer, sql, tablesUsed, formulasApplied, computedValues, assumptions,
               rowsPreview, rowCount, truncated, timingMillis)  ──▶ HTTP 200
```

The connection is **always closed in a `finally` block**. The effective skip-list is honoured at
*every* layer — removed from the schema in F2, then re-enforced by the guard in F5 and the executor in
F6 — and the per-request `maxRows` override is clamped to `aura.ask.max-rows` (it can only lower the
cap) and applied at F5/F6 so the injected `LIMIT` and the driver row cap agree. The `sql` returned is
the **validated, row-capped** query that actually ran, not the raw F4 draft.

### Clarification vs. failure

A **clarification** is a normal, well-formed outcome (not an exception): when F4 cannot answer from
the non-skipped schema it returns a question, the orchestrator returns early with
`AskResponse.clarification(...)`, and the controller surfaces it as **HTTP 400** with the body intact.
A question that targets a *skipped* table is the canonical case — the table is structurally absent from
the schema the model sees, so the engine asks rather than leaks or guesses.

### Error mapping

The controller maps the pipeline's typed exceptions to clean, **sanitized** HTTP errors — never the
password, raw driver text, prompt, or a stack trace; the status carries the category:

| Condition | Status | Source |
|-----------|--------|--------|
| Engine disabled (`aura.ask.enabled=false`) | `503` | controller guard |
| Malformed request / rejected connection details | `400` | `IllegalArgumentException` |
| Clarification needed | `400` | early return (body is the `AskResponse`) |
| Unsafe / ungeneratable SQL | `422` | `UnsafeSqlException`, `QueryExecutionException(UNSAFE_SQL)` |
| LLM call failed | `502` | `LlmException` (non-timeout) |
| Target connection / execution failed | `502` | `SQLException`, `QueryExecutionException(EXECUTION/NOT_READ_ONLY)` |
| Timed out (LLM or query) | `504` | `LlmException(TIMEOUT)`, `QueryExecutionException(TIMEOUT)` |

`AskOrchestratorTest` covers the end-to-end happy path (a question → SQL with an injected `LIMIT`, a
rows preview, a Java-computed `mean`, and an NL answer), the skipped-table clarification (and asserts
the skipped table never appears in the schema shown to the model), and the `maxRows` override
truncating the result — all offline over file-backed SQLite with a capturing stub `LlmClient`.

## Sensitive-data hardening (F9)

F9 strengthens the sensitive-data controls beyond simple table-skipping into three rule kinds — all
carried by one immutable `nlq.schema.SkipList` built once per request and re-checked at every layer.

### The three rule kinds

- **Skipped tables / columns** — *invisible* objects. Removed from the introspected schema (F2), so
  the model never sees them; re-enforced at validation (F5: `UNKNOWN_TABLE` / `SKIPPED_TABLE` /
  `SKIPPED_COLUMN`); and, belt-and-suspenders, dropped from result rows by the redactor (see below).
- **Masked columns** — columns that MAY be *aggregated* (e.g. `count(email)`) but whose **raw values
  must never leave the database**. They stay visible in the rendered schema (marked `MASKED`) so the
  model can aggregate over them; F5 rejects any raw projection (rule 10, `MASKED_COLUMN`); and the
  redactor masks any masked value that still reaches a result.
- **Auto-skip name patterns** — case-insensitive, full-match regexes applied to bare table and column
  names. A match is treated exactly like an explicit skip. They are **on by default** and configurable;
  the defaults cover the common secret-bearing fragments:

  ```
  .*(password|passwd|pwd).*      .*secret.*      .*(^|_)ssn(_|$).*      .*token.*
  .*api[_-]?key.*      .*private[_-]?key.*      .*(credit[_-]?card|card[_-]?number).*
  ```

  So a column named `password_hash`, `api_key`, or `secret_token` is auto-skipped — absent from the
  schema, the SQL, and the output — **even with no explicit request skip**. Toggle the whole feature
  with `aura.ask.auto-skip.enabled=false`; replace `aura.ask.auto-skip.patterns` to change the rules.

### Sources and precedence

The effective policy is the **union** of all of these, gathered in this order (later sources never
*un*-skip an earlier one — union, not override):

1. **Per-request** — `skipTables` / `skipColumns` on the request and on the connection block.
2. **Server-side** — `aura.ask.default-skip-tables`, `aura.ask.default-skip-columns`, and
   `aura.ask.masked-columns`.
3. **Auto-skip patterns** — `aura.ask.auto-skip.patterns` (when enabled).

**Skip beats mask.** A column that matches both a skip rule (explicit *or* an auto-skip pattern) and a
mask rule is *skipped* — removed entirely — never merely masked. Matching is case-insensitive and
schema-qualified-aware, exactly as for the F2 skip-list (`table.column` or `schema.table.column`).

### Value redaction (`nlq.sql.ResultRedactor`)

After F6 execution and **before** the mathematician (F7) or the response sees anything, every row
passes through the redactor, which re-applies the policy at the value level:

- a result column whose leaf name matches a **masked** column has its values **partial-masked** (first
  and last character kept, the middle replaced with `***`; a very short value is fully starred);
- a result column whose leaf name matches a **skipped** column is **dropped entirely** — columns and
  every row cell.

The skipped-column drop matters because `SELECT *` is expanded *by the database* to its real columns,
so a skipped column can re-appear in the result even though it was absent from the schema the model saw
and from the SQL text the guard screened. Redaction never changes the row *count* — only the columns
and the values. F7 therefore computes its statistics over the already-redacted rows, and only redacted
rows ever appear in `rowsPreview`.

### Operator visibility

`SchemaIntrospector` logs, at **DEBUG**, every object the policy removes or masks — by **name only,
never any row value** (introspection reads no rows at all) — e.g.
`Ask skip: column 'accounts.password_hash' excluded from schema` and
`Ask mask: column 'accounts.email' kept for aggregation, raw values redacted`. The redactor likewise
logs, at DEBUG, which result columns it dropped or masked. This lets operators verify the policy
without exposing any sensitive value.

### Acceptance

- A `password_hash` column is auto-skipped by the default patterns and never appears in the schema,
  the SQL, or the output — with no explicit request skip (`SensitiveDataHardeningTest`).
- An `email` column configured as masked can be `count`-ed (`SqlSafetyGuardTest`,
  `AskOrchestratorTest`) but a raw projection of it is rejected (`MASKED_COLUMN`) and any masked value
  that reaches a result is masked (`ResultRedactorTest`).

## Observability & audit (F10)

Every Ask is auditable and the engine surfaces lightweight operational metrics, all under
`nlq.audit`. The goal is that an operator can answer *"what did this request do?"* from one structured
log line — **without** any credential or row value ever being written.

### One record per request

`nlq.api.AskOrchestrator` assembles exactly one `nlq.audit.AskAuditRecord` as the pipeline runs and
hands it to `nlq.audit.AskAuditLogger`, for **every** outcome — answered, clarification, or failure.
The record carries:

| Field | Meaning |
|-------|---------|
| `requestId` | Correlation id (minted by `AskController`), echoed to the client on the answer **and** on error responses. |
| `timestamp` | When the request was received (ISO-8601). |
| `outcome` | `ANSWERED` / `CLARIFICATION` / `ERROR`. |
| `reason` | Sanitized: `clarification`, or a failure category (`unsafe_sql:<Reason>`, `execution:<Kind>`, `llm:<Kind>`, `connect_failed`, `bad_request:<msg>`, `internal_error`) — never raw driver/credential text. |
| `databaseProduct` | Target DB product name (e.g. `PostgreSQL`); `null` if introspection was never reached. |
| `databaseHost` | Target DB `host[:port]` **only**, parsed from the JDBC URL with any `user:pass@` userinfo stripped; `null` for a host-less URL (e.g. SQLite). **Never** the URL, username, or password. |
| `question` | The natural-language question. |
| `generatedSql` | The validated, row-capped SQL that ran; `null` for a clarification or a pre-execution failure. |
| `tablesUsed` | Names of the tables the query read. |
| `rowCount` / `truncated` | Result size and whether the row cap was hit; `rowCount` is `-1` when no query ran. |
| `timingMillis` | The same per-stage latencies returned to the client (`connect`/`introspect`/`generate`/`validate`/`execute`/`redact`/`synthesize`/`total`). |
| `llm` | Token usage for the request: `calls`, `inputTokens`, `outputTokens`. |
| `policy` | The effective `skippedTables`, `skippedColumns`, and `maskedColumns` (names only). |

The log line is emitted as compact JSON at `INFO` on logger `com.lit.fire.flame.nlq.audit.AskAuditLogger`
with an `"event":"ask.request"` discriminator, e.g.:

```json
{"event":"ask.request","requestId":"a1b2…","timestamp":"2026-06-06T01:30:40Z","outcome":"ANSWERED",
 "databaseProduct":"PostgreSQL","databaseHost":"db.internal:5432","question":"average order value last month",
 "generatedSql":"SELECT avg(amount) … LIMIT 500","tablesUsed":["orders"],"rowCount":1,"truncated":false,
 "timingMillis":{"connectMillis":31,"…":0,"totalMillis":2194},"llm":{"calls":3,"inputTokens":1820,"outputTokens":240},
 "policy":{"skippedTables":["audit_log"],"skippedColumns":[],"maskedColumns":["users.email"]}}
```

### Token usage

Token counts are gathered **without** changing the provider-neutral `LlmClient` contract: the
configured client is wrapped in a `nlq.audit.RecordingLlmClient` (wired in `AskEngineConfiguration`)
that meters each completion into a request-scoped, thread-local `nlq.audit.LlmUsageRecorder`. The
orchestrator starts the tally before the pipeline and clears it in a `finally`. Counts a provider
reports as `-1` (unknown) are not added, so an unmetered provider reports `0` tokens (but a non-zero
`calls`).

### Redaction rules

Redaction is **structural** — the record is built to be safe, so the logger just serializes it:

- **No credentials.** Only `databaseHost` (host[:port]) is recorded; the JDBC URL, username, and
  password never appear. `AskAuditLogger.targetHost` strips any `user:pass@` userinfo defensively.
- **No row values.** The record summarizes results by `rowCount`/`truncated` and by table/column
  **names** only — never a cell. In particular a **masked** column's raw value is never logged
  (consistent with F9; `generatedSql` may contain `count(email)`, which is a name, not a value).
- **No API keys or prompts.** The token recorder reads only the numeric counts already on
  `LlmResponse`; prompt/completion text is never touched (the `LlmClient` already never logs them).
- **Sanitized failure reasons.** Error outcomes record a category, never the raw driver `SQLException`
  text (which can echo connection details).

### Optional persistence

By default the engine is **log-only**. Set `aura.ask.audit.persist=true` to also write each record to
a table in **AuraMath's own database** — via the application `JdbcTemplate` from `DataSourceConfig`,
**never** the per-request target connection. The table is **not** auto-created (we never run DDL
against an arbitrary database); create it yourself. The default table name is `ask_audit_log`
(`aura.ask.audit.table`). Suggested DDL (PostgreSQL):

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

A persistence failure is swallowed with a warning (request id + exception class only) so auditing
never breaks the request it records.

### Metrics

Rather than add Spring Boot Actuator / Micrometer, `nlq.audit.AskMetrics` keeps process-wide,
in-memory counters bumped by the orchestrator and exposes them at **`GET /api/ask/admin/metrics`**
(`nlq.api.AskMetricsController`) as counts only: `requests`, `answers`, `clarifications`, `errors`,
`unsafeSqlRejections`, `executionTimeouts`, and `llmFailures`. Counts are monotonic since process
start and reset on restart.

### Acceptance

A successful request and a rejected one (e.g. a `MASKED_COLUMN` rejection) each emit a complete,
credential-free audit line whose `requestId` matches the id echoed to the client (on `AskResponse` and
`AskErrorResponse` respectively); no masked value ever appears in the log
(`AskOrchestratorTest` exercises both paths and asserts the id echo and the metric counters).

## Configuration

Bound from prefix `aura.ask` (`nlq.config.AskEngineProperties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `aura.ask.enabled` | `true` | Master switch for the engine. |
| `aura.ask.max-rows` | `1000` | Max rows any generated query may return. |
| `aura.ask.query-timeout-seconds` | `30` | Per-query execution timeout. |
| `aura.ask.connection-timeout-seconds` | `10` | Target connection establishment timeout. |
| `aura.ask.default-skip-tables` | _(empty)_ | Tables always excluded, on top of per-request skips. |
| `aura.ask.default-skip-columns` | _(empty)_ | Columns always excluded, on top of per-request skips. (F9) |
| `aura.ask.masked-columns` | _(empty)_ | Columns aggregatable but never returned raw; values redacted. (F9) |
| `aura.ask.auto-skip.enabled` | `true` | Toggle for pattern-based auto-skipping of secret-named objects. (F9) |
| `aura.ask.auto-skip.patterns` | _(see above)_ | Case-insensitive, full-match name regexes auto-skipped. (F9) |
| `aura.ask.audit.persist` | `false` | Also persist each audit record to AuraMath's own DB (log-only when `false`). (F10) |
| `aura.ask.audit.table` | `ask_audit_log` | Target table for persisted audit records (not auto-created). (F10) |
| `aura.ask.llm-provider` | `claude` | Selected `LlmClient` provider. |

The LLM API key is read from `secrets.txt` (`anthropic.api.key=`) and is never committed. The active
provider is chosen by `aura.ask.llm-provider` (only `claude` is implemented today; any other value
fails fast at startup).

## Feature checklist

- [x] **F0** — Module scaffold, dependencies & config (this document, packages, properties).
- [x] **F1** — Per-request isolated target connections + `POST /api/ask/test-connection` (`nlq.connection`, `nlq.api`).
- [x] **F2** — Schema introspection with skip-list filtering (`nlq.schema`).
- [x] **F3** — `LlmClient` interface + Claude implementation (`nlq.llm`).
- [x] **F4** — NL → SQL prompting (`nlq.sql`).
- [x] **F5** — Read-only + skip-list SQL validation (`nlq.sql`).
- [x] **F6** — Bounded, timed query execution (`nlq.sql`).
- [x] **F7** — Mathematician layer: plan-then-deterministically-evaluate + answer synthesis (`nlq.math`).
- [x] **F8** — Orchestrator (`AskOrchestrator`) + `POST /api/ask` endpoint and DTOs (`AskRequest`/`AskResponse`/`AskErrorResponse`), wiring F1–F7 end to end (`nlq.api`).
- [x] **F9** — Sensitive-data hardening: skipped/masked columns, auto-skip name patterns, and output redaction (`nlq.schema`, `nlq.sql`).
- [x] **F10** — Audit logging & observability: per-request credential-free audit record + structured log line, optional persistence, request-id correlation, and metrics (`nlq.audit`, `nlq.api`).
- [ ] **F11** — Full end-to-end integration tests against Postgres/MySQL targets + remaining hardening.
