package com.lit.fire.flame.nlq.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable, skip-list-filtered snapshot of a target database's structure: the user tables/views
 * that survived filtering, plus the reported product name and a coarse dialect hint. This is the
 * single object later features render and feed to the LLM.
 *
 * <p>Contains structure only — never any row data.
 */
public final class DatabaseSchema {

    private final List<TableInfo> tables;
    /** Raw product name from {@code DatabaseMetaData.getDatabaseProductName} (e.g. "PostgreSQL"). */
    private final String productName;
    /** Coarse dialect hint derived from the product name (e.g. "postgresql", "sqlite", "mysql"). */
    private final String dialect;

    public DatabaseSchema(List<TableInfo> tables, String productName, String dialect) {
        this.tables = Collections.unmodifiableList(new ArrayList<>(tables));
        this.productName = productName;
        this.dialect = dialect;
    }

    public List<TableInfo> getTables() {
        return tables;
    }

    public String getProductName() {
        return productName;
    }

    public String getDialect() {
        return dialect;
    }
}
