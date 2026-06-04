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
- **Skip-list.** A request may name tables/columns to skip. These are excluded from the schema the
  model sees (step 2) **and** re-enforced at validation/execution (steps 4–5), so the model can
  neither see nor touch them. `aura.ask.default-skip-tables` is always applied on top of the
  per-request list.
- **Pluggable LLM.** The model layer sits behind an `LlmClient` interface in `nlq.llm`; the Claude
  implementation is first. The active provider is selected via `aura.ask.llm-provider`.

## Configuration

Bound from prefix `aura.ask` (`nlq.config.AskEngineProperties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `aura.ask.enabled` | `true` | Master switch for the engine. |
| `aura.ask.max-rows` | `1000` | Max rows any generated query may return. |
| `aura.ask.query-timeout-seconds` | `30` | Per-query execution timeout. |
| `aura.ask.connection-timeout-seconds` | `10` | Target connection establishment timeout. |
| `aura.ask.default-skip-tables` | _(empty)_ | Tables always excluded, on top of per-request skips. |
| `aura.ask.llm-provider` | `claude` | Selected `LlmClient` provider. |

The LLM API key is read from `secrets.txt` (`anthropic.api.key=`) and is never committed.

## Feature checklist

- [x] **F0** — Module scaffold, dependencies & config (this document, packages, properties).
- [ ] **F1** — Per-request isolated target connections (`nlq.connection`).
- [ ] **F2** — Schema introspection with skip-list filtering (`nlq.schema`).
- [ ] **F3** — `LlmClient` interface + Claude implementation (`nlq.llm`).
- [ ] **F4** — NL → SQL prompting (`nlq.sql`).
- [ ] **F5** — Read-only + skip-list SQL validation (`nlq.sql`).
- [ ] **F6** — Bounded, timed query execution (`nlq.sql`).
- [ ] **F7** — Result-set mathematical post-processing (`nlq.math`).
- [ ] **F8** — Natural-language answer composition.
- [ ] **F9** — REST API + DTOs (`nlq.api`).
- [ ] **F10** — End-to-end integration tests.
- [ ] **F11** — Hardening, observability, and docs.
