package com.lit.fire.flame.nlq.connection;

import com.lit.fire.flame.nlq.config.AskEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The host-side registry of named target databases the Ask engine may answer against.
 *
 * <p>It is loaded <b>once at startup</b> from a credential file on the machine running AuraMath
 * (path {@code aura.ask.secrets-path}, default {@code ~/config.secrets}), so credentials never travel
 * in a request body or live in the repository. Each database is one {@code ask.db.<name>.*} group:
 *
 * <pre>
 *   ask.db.orders.driver=mysql
 *   ask.db.orders.url=jdbc:mysql://localhost:3306/ordersdb
 *   ask.db.orders.username=ro
 *   ask.db.orders.password=secret
 *   ask.db.orders.skipColumns=customers.ssn
 * </pre>
 *
 * <p>Each group becomes a {@link ConnectionRequest} (with its {@link ConnectionRequest#getName() name}
 * set to {@code <name>}). Every URL is validated with {@link JdbcUrlValidator} at load time; an entry
 * that is missing a URL or fails validation is <b>skipped with a sanitized warning</b> rather than
 * crashing the application. A missing file yields an empty registry — the per-request connection path
 * still works.
 *
 * <p><b>No secrets leak.</b> This class never logs usernames or passwords; only database names, the
 * resolved driver, and a host[:port] (via {@code DriverManager}-free string parsing) are ever logged
 * or exposed. Callers that surface registry contents (e.g. the discovery endpoint) must use
 * {@link #describe()}, which omits credentials by construction.
 */
@Component
public class DatasourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatasourceRegistry.class);

    /** Prefix that marks a database group: {@code ask.db.<name>.<field>}. */
    private static final String PREFIX = "ask.db.";

    /** Insertion-ordered map of database name → its (credential-bearing) connection details. */
    private final Map<String, ConnectionRequest> byName;

    public DatasourceRegistry(AskEngineProperties properties) {
        this(properties.getSecretsPath());
    }

    /** Package/test-friendly constructor that loads directly from an explicit file path. */
    DatasourceRegistry(String secretsPath) {
        this.byName = load(secretsPath);
        log.info("Ask datasource registry loaded {} database(s): {}", byName.size(), byName.keySet());
    }

    /** An empty registry (no host credential file). Useful when only per-request connections are used. */
    public static DatasourceRegistry empty() {
        return new DatasourceRegistry((String) null);
    }

    /** Load a registry directly from a credential file at {@code path} (e.g. for tests/tools). */
    public static DatasourceRegistry fromSecretsFile(String path) {
        return new DatasourceRegistry(path);
    }

    /** The registered database names, in file order; never {@code null}. */
    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    /** Whether a database with this (case-insensitive) name is registered. */
    public boolean contains(String name) {
        return resolveKey(name) != null;
    }

    /**
     * The connection details for {@code name} (case-insensitive), or {@code null} if not registered.
     * The returned object carries credentials — never log or echo it.
     */
    public ConnectionRequest get(String name) {
        String key = resolveKey(name);
        return key == null ? null : byName.get(key);
    }

    /** All registered connections, in file order; never {@code null}. The objects carry credentials. */
    public List<ConnectionRequest> all() {
        return new ArrayList<>(byName.values());
    }

    /** Number of registered databases. */
    public int size() {
        return byName.size();
    }

    /**
     * A credential-free description of each registered database — name, resolved driver alias, and
     * host[:port] only. Safe to return from an API or write to a log.
     */
    public List<DatasourceInfo> describe() {
        List<DatasourceInfo> out = new ArrayList<>(byName.size());
        for (ConnectionRequest c : byName.values()) {
            out.add(new DatasourceInfo(c.getName(), driverAlias(c), hostOf(c.getJdbcUrl())));
        }
        return out;
    }

    private String resolveKey(String name) {
        if (name == null) {
            return null;
        }
        String target = name.trim();
        for (String key : byName.keySet()) {
            if (key.equalsIgnoreCase(target)) {
                return key;
            }
        }
        return null;
    }

    // --- loading ---------------------------------------------------------------------------------

    private static Map<String, ConnectionRequest> load(String secretsPath) {
        Map<String, ConnectionRequest> result = new LinkedHashMap<>();
        if (secretsPath == null || secretsPath.isBlank()) {
            return result;
        }
        Path path = Path.of(secretsPath.trim());
        if (!Files.isRegularFile(path)) {
            log.info("Ask datasource registry: no credential file at the configured path; "
                    + "starting with an empty registry");
            return result;
        }
        Properties props = new Properties();
        List<String> lines;
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            // Never echo the path's contents; the message stays generic.
            log.warn("Ask datasource registry: could not read the credential file ({}); "
                    + "starting with an empty registry", e.getClass().getSimpleName());
            return result;
        }

        // Determine the database names in true file order (Properties does not preserve order).
        Map<String, Boolean> order = new LinkedHashMap<>();
        for (String name : namesInFileOrder(lines)) {
            order.putIfAbsent(name, Boolean.TRUE);
        }
        // Defensive: include any name present in props but not matched above (e.g. line-continuations).
        for (String key : new TreeSet<>(props.stringPropertyNames())) {
            String name = databaseName(key);
            if (name != null) {
                order.putIfAbsent(name, Boolean.TRUE);
            }
        }

        for (String name : order.keySet()) {
            ConnectionRequest connection = buildOne(name, props);
            if (connection != null) {
                result.put(name, connection);
            }
        }
        return result;
    }

    /** The {@code ask.db.<name>} names in the order they first appear across the raw file lines. */
    private static List<String> namesInFileOrder(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = indexOfAny(line, '=', ':');
            String key = (eq < 0) ? line : line.substring(0, eq).trim();
            String name = databaseName(key);
            if (name != null && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Extract {@code <name>} from a {@code ask.db.<name>.<field>} key, or {@code null} if it is not one. */
    private static String databaseName(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String rest = key.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        return (dot <= 0) ? null : rest.substring(0, dot);
    }

    /** Build one validated {@link ConnectionRequest} from {@code ask.db.<name>.*}, or {@code null}. */
    private static ConnectionRequest buildOne(String name, Properties props) {
        String base = PREFIX + name + ".";
        String url = trimToNull(props.getProperty(base + "url"));
        if (url == null) {
            log.warn("Ask datasource '{}' has no '{}url' — skipping", name, base);
            return null;
        }
        String driver = trimToNull(props.getProperty(base + "driver"));
        try {
            // Fail-closed scheme/denylist check now, so a bad URL is caught at startup, not per request.
            JdbcUrlValidator.validateAndResolve(url, driver);
        } catch (IllegalArgumentException e) {
            // e.getMessage() is credential-free by JdbcUrlValidator's contract.
            log.warn("Ask datasource '{}' has an invalid JDBC URL ({}) — skipping", name, e.getMessage());
            return null;
        }

        ConnectionRequest connection = new ConnectionRequest();
        connection.setName(name);
        connection.setJdbcUrl(url);
        connection.setUsername(trimToNull(props.getProperty(base + "username")));
        connection.setPassword(emptyToNull(props.getProperty(base + "password")));
        connection.setDriver(driver);
        connection.setSkipTables(csv(props.getProperty(base + "skipTables")));
        connection.setSkipColumns(csv(props.getProperty(base + "skipColumns")));
        return connection;
    }

    // --- credential-free helpers ----------------------------------------------------------------

    private static String driverAlias(ConnectionRequest c) {
        try {
            return JdbcUrlValidator.validateAndResolve(c.getJdbcUrl(), c.getDriver()).getAlias();
        } catch (RuntimeException e) {
            return c.getDriver();
        }
    }

    /**
     * Extract a credential-free host[:port] from a JDBC URL for display/logging. Mirrors the intent of
     * {@code AskAuditLogger.targetHost} but local to avoid a cross-package dependency.
     */
    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String url = jdbcUrl.trim();
        // jdbc:sqlite:/path → file-based, no host.
        if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:")) {
            return "(file)";
        }
        int authority = url.indexOf("//");
        if (authority < 0) {
            return null;
        }
        String after = url.substring(authority + 2);
        int end = indexOfAny(after, '/', '?', ';');
        String hostPort = (end < 0) ? after : after.substring(0, end);
        // Strip any user:pass@ that some URLs embed before the host.
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        return hostPort.isEmpty() ? null : hostPort;
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String trimToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** Password may be intentionally empty (e.g. trust auth); keep "" distinct only by nulling blanks. */
    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** Credential-free view of a registered database for discovery/logging. */
    public static final class DatasourceInfo {
        private final String name;
        private final String driver;
        private final String host;

        public DatasourceInfo(String name, String driver, String host) {
            this.name = name;
            this.driver = driver;
            this.host = host;
        }

        public String getName() {
            return name;
        }

        public String getDriver() {
            return driver;
        }

        public String getHost() {
            return host;
        }
    }
}
