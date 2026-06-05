package com.lit.fire.flame.nlq.connection;

import java.util.List;
import java.util.Locale;

/**
 * Fail-closed validation of a target JDBC URL before any connection attempt.
 *
 * <p>Two gates, both of which must pass:
 * <ol>
 *   <li><b>Scheme whitelist</b> — the URL must begin with a {@link SupportedDriver} prefix
 *       ({@code jdbc:postgresql:}, {@code jdbc:sqlite:}, or {@code jdbc:mysql:}). Anything else
 *       (e.g. {@code jdbc:h2:}, {@code jdbc:oracle:}) is rejected.</li>
 *   <li><b>Parameter denylist</b> — the URL must not contain any known-dangerous JDBC parameter
 *       substring (file/local-infile loading, custom socket factories, init statements, gadget
 *       deserialization). These enable RCE / local-file exfiltration on several drivers and have no
 *       legitimate use for a read-only analytics connection.</li>
 * </ol>
 *
 * <p>The class is stateless; all entry points are static.
 */
public final class JdbcUrlValidator {

    /**
     * Case-insensitive substrings that, if present anywhere in the URL, cause rejection. Kept small
     * and conservative — fail closed rather than enumerate every safe parameter.
     */
    static final List<String> DENYLIST = List.of(
            "allowloadlocalinfile",
            "socketfactory",
            "init",
            "autodeserialize",
            "allowurlinlocalinfile");

    private JdbcUrlValidator() {
    }

    /**
     * Validate {@code jdbcUrl} and resolve the driver to use. When {@code driverHint} is non-blank
     * it must agree with the URL scheme; otherwise the driver is auto-detected from the scheme.
     *
     * @return the resolved {@link SupportedDriver}
     * @throws IllegalArgumentException if the URL is missing, its scheme is not whitelisted, it
     *                                  contains a denylisted parameter, or the hint conflicts with
     *                                  the scheme. The message never contains credentials.
     */
    public static SupportedDriver validateAndResolve(String jdbcUrl, String driverHint) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required");
        }
        String trimmed = jdbcUrl.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        SupportedDriver bySchemeDriver = SupportedDriver.fromUrl(trimmed);
        if (bySchemeDriver == null) {
            throw new IllegalArgumentException(
                    "Unsupported JDBC URL scheme. Allowed: jdbc:postgresql:, jdbc:sqlite:, jdbc:mysql:");
        }

        for (String banned : DENYLIST) {
            if (lower.contains(banned)) {
                throw new IllegalArgumentException(
                        "JDBC URL contains a disallowed parameter: '" + banned + "'");
            }
        }

        if (driverHint != null && !driverHint.isBlank()) {
            SupportedDriver byHint = SupportedDriver.fromAlias(driverHint);
            if (byHint == null) {
                throw new IllegalArgumentException(
                        "Unsupported driver '" + driverHint + "'. Allowed: postgresql, sqlite, mysql");
            }
            if (byHint != bySchemeDriver) {
                throw new IllegalArgumentException(
                        "driver '" + driverHint + "' does not match the JDBC URL scheme");
            }
        }

        return bySchemeDriver;
    }
}
