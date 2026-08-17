package com.lit.fire.flame;

import com.fasterxml.jackson.core.JsonGenerator;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Properties;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource() {
        Properties dbProperties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("secrets.txt")) {
            if (in != null) {
                dbProperties.load(in);
            }
        } catch (Exception e) {
            // Log the exception or handle it as per application's error handling strategy
            e.printStackTrace();
        }

        // Was a plain DriverManagerDataSource, which opens a brand-new physical connection per
        // JDBC call and closes it right after. Fine for a single occasional query, but any bulk
        // per-row write loop (e.g. UserEngagementRatingService's per-user UPDATE loop over tens
        // of thousands of marketing_target_profiles rows) opens/closes connections fast enough to
        // exhaust ephemeral ports mid-run and fail with "connection attempt failed". HikariCP is
        // already a transitive dependency via spring-boot-starter-jdbc.
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(dbProperties.getProperty("db.url", "jdbc:postgresql://localhost:5432/aura"));
        dataSource.setUsername(dbProperties.getProperty("db.user", "postgres"));
        dataSource.setPassword(dbProperties.getProperty("db.password", "postgres"));
        // hawkesIntensityCalculator() below checks out and permanently holds one connection for
        // the application's lifetime, so the pool is sized with that pinned connection in mind.
        dataSource.setMaximumPoolSize(15);

        return dataSource;
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder.featuresToDisable(JsonGenerator.Feature.ESCAPE_NON_ASCII);
    }

    @Bean
    public AspectSentimentAnalyzer aspectSentimentAnalyzer() {
        return new AspectSentimentAnalyzer();
    }

    /**
     * Exponential-kernel decay rate (per HOUR — event times are rescaled to hours in
     * {@link HawkesIntensityCalculator}). beta sets both the self-excitation half-life
     * (ln 2 / beta hours) and the stationarity ceiling on alpha (0 <= alpha < beta).
     * Raised from the historical 1.0 so bursty cascade-starters are no longer clipped at
     * the alpha = 1.0 boundary. Tune via the {@code hawkes.beta} property.
     */
    @Bean
    public HawkesIntensityCalculator hawkesIntensityCalculator(
            DataSource dataSource,
            @Value("${hawkes.beta:3.0}") double beta) throws SQLException {
        return new HawkesIntensityCalculator(dataSource.getConnection(), beta);
    }
}
