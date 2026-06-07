package com.lit.fire.flame.nlq.schema;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import com.lit.fire.flame.nlq.connection.ConnectionRequest;
import com.lit.fire.flame.nlq.connection.DatasourceRegistry;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;

/**
 * Periodically (hourly by default) re-introspects every registered database and stores its structured
 * schema in the {@link SchemaCacheService} (F13), so the Ask engine answers from a cached schema rather
 * than introspecting each target live on every question.
 *
 * <p>Runs shortly after startup (so the cache is warm) and then on a fixed interval
 * ({@code aura.ask.schema-cache.refresh-interval-ms}). Each database is refreshed independently: a
 * failure on one (unreachable host, bad credentials) is logged — class name only, never credentials —
 * and the rest still refresh. It opens each connection <b>read-only</b> via the same
 * {@link DynamicConnectionFactory} the request path uses, and introspects through the connection's
 * request-independent base skip-list so cached schemas already exclude server/connection-skipped objects.
 */
@Service
public class SchemaRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(SchemaRefreshJob.class);

    private final DatasourceRegistry registry;
    private final DynamicConnectionFactory connectionFactory;
    private final SchemaIntrospector introspector;
    private final SchemaCacheService schemaCache;
    private final AskEngineProperties properties;

    public SchemaRefreshJob(DatasourceRegistry registry,
                            DynamicConnectionFactory connectionFactory,
                            SchemaIntrospector introspector,
                            SchemaCacheService schemaCache,
                            AskEngineProperties properties) {
        this.registry = registry;
        this.connectionFactory = connectionFactory;
        this.introspector = introspector;
        this.schemaCache = schemaCache;
        this.properties = properties;
    }

    /**
     * Refresh the cached schema for every registered database. Scheduled after a short startup delay and
     * then on {@code aura.ask.schema-cache.refresh-interval-ms} (default hourly). Never throws.
     */
    @Scheduled(initialDelayString = "${aura.ask.schema-cache.initial-delay-ms:30000}",
            fixedRateString = "${aura.ask.schema-cache.refresh-interval-ms:3600000}")
    public void refresh() {
        if (!properties.getSchemaCache().isEnabled() || !schemaCache.isReady()) {
            return;
        }
        if (registry.size() == 0) {
            return;
        }
        int ok = 0;
        int failed = 0;
        for (ConnectionRequest connection : registry.all()) {
            if (refreshOne(connection)) {
                ok++;
            } else {
                failed++;
            }
        }
        log.info("Ask schema cache refresh complete: {} refreshed, {} failed", ok, failed);
    }

    private boolean refreshOne(ConnectionRequest connection) {
        try (Connection conn = connectionFactory.open(connection)) {
            DatabaseSchema schema = introspector.introspect(conn,
                    SkipListFactory.base(connection, properties));
            schemaCache.put(connection.getName(), schema);
            return true;
        } catch (Exception e) {
            // Never log credentials or the URL — only the logical name and the exception class.
            log.warn("Ask schema cache: failed to refresh database '{}': {}",
                    connection.getName(), e.getClass().getSimpleName());
            return false;
        }
    }
}
