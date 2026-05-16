package com.lit.fire.flame;

import com.fasterxml.jackson.core.JsonGenerator;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(dbProperties.getProperty("db.url", "jdbc:postgresql://localhost:5432/aura"));
        dataSource.setUsername(dbProperties.getProperty("db.user", "postgres"));
        dataSource.setPassword(dbProperties.getProperty("db.password", "postgres"));

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

    @Bean
    public HawkesIntensityCalculator hawkesIntensityCalculator(DataSource dataSource) throws SQLException {
        return new HawkesIntensityCalculator(dataSource.getConnection(), 1.0);
    }
}
