package com.vishwas.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Production uses PostgreSQL. Hosting platforms hand out {@code DATABASE_URL=postgres://user:pass@host:port/db},
 * which JDBC does not understand, so we translate it. A {@code jdbc:} URL is used as-is with
 * {@code DATABASE_USERNAME}/{@code DATABASE_PASSWORD}. Without DATABASE_URL the local H2 file database is used.
 */
@Configuration
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class DatabaseUrlConfig {

    @Bean
    public DataSource dataSource(Environment env) {
        String raw = env.getProperty("DATABASE_URL", "").trim();
        if (raw.startsWith("jdbc:")) {
            return DataSourceBuilder.create().url(raw)
                    .username(env.getProperty("DATABASE_USERNAME"))
                    .password(env.getProperty("DATABASE_PASSWORD"))
                    .build();
        }
        URI uri = URI.create(raw);
        String[] userInfo = uri.getUserInfo() == null ? new String[0] : uri.getUserInfo().split(":", 2);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        return DataSourceBuilder.create()
                .driverClassName("org.postgresql.Driver")
                .url("jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query)
                .username(userInfo.length > 0 ? userInfo[0] : null)
                .password(userInfo.length > 1 ? userInfo[1] : null)
                .build();
    }
}
