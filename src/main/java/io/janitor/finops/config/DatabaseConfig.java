package io.janitor.finops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * SQLite bootstrap.
 *
 * Why SQLite?
 *   • Zero background process — it is literally a file on disk.
 *   • 0 MB background RAM usage.
 *   • On a 128 GB SSD this is perfect; no need for Postgres/MySQL containers.
 *
 * What is stored?
 *   hibernate_log — every action the Janitor takes (namespace, action, timestamp,
 *   original replica count, AI reasoning).  This is the data that backs the
 *   "95 % accuracy" metric and the cost-savings dashboard.
 */
@Configuration
public class DatabaseConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseConfig.class);

    /**
     * Runs schema.sql automatically on every startup.
     * The SQL uses CREATE TABLE IF NOT EXISTS so restarts are idempotent.
     */
    @Bean
    public DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(
                new ClassPathResource("schema.sql")
                        .getClassLoader()
                        .getResource("schema.sql") != null
                        ? new org.springframework.core.io.ClassPathResource("schema.sql")
                        : null
        );
        // Simpler approach: just use the addScript directly
        populator.addScript(new org.springframework.core.io.ClassPathResource("schema.sql"));
        populator.setContinueOnError(false);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);

        LOG.info("[DB] SQLite schema initialized.");
        return initializer;
    }
}
