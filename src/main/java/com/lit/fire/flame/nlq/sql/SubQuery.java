package com.lit.fire.flame.nlq.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One per-database sub-query of a {@link FederatedSqlPlan} — a single read-only {@code SELECT}/{@code WITH}
 * drafted to run against exactly one named database. A federated Ask cannot JOIN across separate JDBC
 * connections, so the model emits one of these per database it needs; each is validated (F5) and
 * executed (F6) on its own connection before the results are collated.
 *
 * <p>The {@link #getSql() SQL} is a <b>draft</b> — still untrusted until it passes F5 validation.
 */
public final class SubQuery {

    private final String database;
    private final String sql;
    private final List<String> tablesUsed;

    public SubQuery(String database, String sql, List<String> tablesUsed) {
        this.database = database;
        this.sql = sql;
        this.tablesUsed = copy(tablesUsed);
    }

    /** The logical database name this query targets, as labeled in the prompt; never {@code null}. */
    public String getDatabase() {
        return database;
    }

    /** The drafted (unvalidated) read-only query for {@link #getDatabase()}; never {@code null}. */
    public String getSql() {
        return sql;
    }

    /** Tables this query reads, exactly as named in that database's schema; never {@code null}. */
    public List<String> getTablesUsed() {
        return tablesUsed;
    }

    private static List<String> copy(List<String> in) {
        return (in == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(in));
    }
}
