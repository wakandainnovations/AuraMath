package com.lit.fire.flame.nlq.schema;

import java.util.Objects;

/**
 * A {@link DatabaseSchema} paired with the logical database name it was introspected from. Used by the
 * federated (multi-database) Ask path so the model — which sees several independent schemas at once —
 * can label which database each table lives in and emit one sub-query per database.
 */
public final class NamedSchema {

    private final String name;
    private final DatabaseSchema schema;

    public NamedSchema(String name, DatabaseSchema schema) {
        this.name = Objects.requireNonNull(name, "name");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /** The logical database name (e.g. {@code orders}); never {@code null}. */
    public String getName() {
        return name;
    }

    /** The skip-list-filtered schema for this database; never {@code null}. */
    public DatabaseSchema getSchema() {
        return schema;
    }
}
