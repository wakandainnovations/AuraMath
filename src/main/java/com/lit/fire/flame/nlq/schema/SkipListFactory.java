package com.lit.fire.flame.nlq.schema;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.ConnectionRequest;

import java.util.List;

/**
 * Builds the effective {@link SkipList} for the Ask engine from the layered sources, so the schema
 * refresh job (F13) and the request path agree on exactly the same policy.
 *
 * <ul>
 *   <li><b>{@link #base(ConnectionRequest, AskEngineProperties) base}</b> — the request-independent
 *       policy: server-side {@code default-skip-*} + {@code masked-columns} + auto-skip patterns, plus
 *       the connection's own skip lists. This is what the schema cache is built with.</li>
 *   <li><b>{@link #effective(List, List, ConnectionRequest, AskEngineProperties) effective}</b> — the
 *       base plus a single request's extra {@code skipTables}/{@code skipColumns}. This is what a live
 *       request enforces and filters the cached schema with.</li>
 * </ul>
 */
public final class SkipListFactory {

    private SkipListFactory() {
    }

    /** The request-independent policy for a connection (what the schema cache is built with). */
    public static SkipList base(ConnectionRequest connection, AskEngineProperties properties) {
        return populate(SkipList.builder(), connection, properties).build();
    }

    /** The base policy plus one request's extra skip lists. */
    public static SkipList effective(List<String> requestSkipTables, List<String> requestSkipColumns,
                                     ConnectionRequest connection, AskEngineProperties properties) {
        SkipList.Builder builder = populate(SkipList.builder(), connection, properties);
        builder.addSkipTables(requestSkipTables);
        builder.addSkipColumns(requestSkipColumns);
        return builder.build();
    }

    private static SkipList.Builder populate(SkipList.Builder builder, ConnectionRequest connection,
                                             AskEngineProperties properties) {
        AskEngineProperties.AutoSkip autoSkip = properties.getAutoSkip();
        builder.addSkipTables(properties.getDefaultSkipTables());
        builder.addSkipColumns(properties.getDefaultSkipColumns());
        if (connection != null) {
            builder.addSkipTables(connection.getSkipTables());
            builder.addSkipColumns(connection.getSkipColumns());
        }
        builder.addMaskedColumns(properties.getMaskedColumns());
        builder.autoSkipPatterns(autoSkip.getPatterns(), autoSkip.isEnabled());
        return builder;
    }
}
