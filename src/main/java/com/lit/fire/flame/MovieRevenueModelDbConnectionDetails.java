package com.lit.fire.flame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the individual host/port/name/user/password fields the movie-revenue
 * Python scripts' {@code --db-*} CLI flags need out of the same {@code secrets.txt}
 * {@link DataSourceConfig} already loads (as one JDBC URL) -- shared by
 * {@link MovieRevenuePredictionService} (Feature 9's on-demand /predict) and
 * {@link MovieRevenuePredictionScheduler} (Feature 10's weekly batch re-scoring)
 * so this parsing lives in exactly one place.
 */
final class MovieRevenueModelDbConnectionDetails {

    private static final Logger log = LoggerFactory.getLogger(MovieRevenueModelDbConnectionDetails.class);
    private static final Pattern JDBC_URL_PATTERN =
        Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)");

    final String host;
    final String port;
    final String name;
    final String user;
    final String password;

    private MovieRevenueModelDbConnectionDetails(String host, String port, String name,
                                                  String user, String password) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.user = user;
        this.password = password;
    }

    static MovieRevenueModelDbConnectionDetails load() {
        Properties dbProperties = new Properties();
        try (InputStream in = MovieRevenueModelDbConnectionDetails.class.getClassLoader()
                .getResourceAsStream("secrets.txt")) {
            if (in != null) {
                dbProperties.load(in);
            }
        } catch (IOException e) {
            log.warn("Could not load secrets.txt: {}", e.getMessage());
        }
        String url = dbProperties.getProperty("db.url", "jdbc:postgresql://localhost:5432/aura");
        String user = dbProperties.getProperty("db.user", "postgres");
        String password = dbProperties.getProperty("db.password", "postgres");

        Matcher matcher = JDBC_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException("Could not parse db.url from secrets.txt: " + url);
        }
        return new MovieRevenueModelDbConnectionDetails(
            matcher.group(1), matcher.group(2), matcher.group(3), user, password);
    }
}
