package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.connection.ConnectionRequest;
import com.lit.fire.flame.nlq.connection.DynamicConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * REST entry point for the Ask engine's connection layer (F1).
 *
 * <p>Only exposes a connection-test probe for now; schema introspection and the NL → SQL pipeline
 * land in later features. All connections it opens are read-only and fully isolated from AuraMath's
 * own datasource.
 */
@RestController
@RequestMapping("/api/ask")
public class AskConnectionController {

    private final DynamicConnectionFactory connectionFactory;

    public AskConnectionController(DynamicConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Attempt a read-only connection to the supplied target database and run a trivial
     * {@code SELECT 1} probe.
     *
     * <p>Returns {@code 400} for a request the factory rejects outright (unknown scheme/driver,
     * denylisted JDBC parameter) and {@code 200} with {@code connected=false} when the connection
     * or probe itself fails. The password is never included in the response or any log.
     */
    @PostMapping("/test-connection")
    public ResponseEntity<ConnectionTestResult> testConnection(@RequestBody ConnectionRequest request) {
        try (Connection connection = connectionFactory.open(request)) {
            // Driver-appropriate trivial probe — SELECT 1 is valid on Postgres, SQLite and MySQL.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT 1")) {
                rs.next();
            }
            DatabaseMetaData meta = connection.getMetaData();
            return ResponseEntity.ok(ConnectionTestResult.success(
                    meta.getDatabaseProductName(), meta.getDatabaseProductVersion()));
        } catch (IllegalArgumentException e) {
            // Validation / fail-closed rejection — bad request, no credentials in the message.
            return ResponseEntity.badRequest().body(ConnectionTestResult.failure(e.getMessage()));
        } catch (SQLException e) {
            // Connection or probe failure — reachable target, but could not connect/query.
            return ResponseEntity.ok(ConnectionTestResult.failure(e.getMessage()));
        }
    }
}
