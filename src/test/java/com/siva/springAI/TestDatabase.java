package com.siva.springAI;

import com.siva.springAI.service.ConversationMemory;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Each test owns a fresh schema, migrated by the same Flyway scripts as production. */
public final class TestDatabase {
    public final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TestDatabase() {
        String name = "spring_ai_test_" + UUID.randomUUID().toString().replace("-", "");
        String mysqlPort = System.getProperty("test.mysql.port");
        String url = mysqlPort == null
                ? "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                : "jdbc:mysql://127.0.0.1:" + Integer.parseInt(mysqlPort) + "/" + name + "?createDatabaseIfNotExist=true&connectionTimeZone=UTC";
        var source = new DriverManagerDataSource(url, mysqlPort == null ? "sa" : "root", "");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    public ConversationMemory memory(int turns, int characters) {
        return new ConversationMemory(jdbc, transactions, turns, characters, 300);
    }
}
