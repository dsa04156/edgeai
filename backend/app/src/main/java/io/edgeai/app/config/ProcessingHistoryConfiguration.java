package io.edgeai.app.config;

import com.zaxxer.hikari.*;
import io.edgeai.adapters.repository.JdbcProcessingHistoryRepository;
import io.edgeai.domain.repository.ProcessingHistoryRepository;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
class ProcessingHistoryConfiguration {
    @Bean(destroyMethod="close") HistoryStore processingHistoryRepository(@Value("${edgeai.continuous.history.url:}") String url,
        @Value("${edgeai.continuous.history.user:edgeai_history}") String user,@Value("${edgeai.continuous.history.password:}") String password,
        @Value("${spring.datasource.url}") String mainUrl,@Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        if(!url.isBlank() && (!url.startsWith("jdbc:postgresql://") || java.net.URI.create(url.substring(5)).getPath().equals(java.net.URI.create(mainUrl.substring(5)).getPath())))throw new IllegalArgumentException("Processing history requires a separate PostgreSQL database name");
        return new HistoryStore(url,user,password,inspection);
    }
    /** Encapsulation preserves the main DataSource/Flyway auto configuration and isolates history outages. */
    static final class HistoryStore implements ProcessingHistoryRepository,AutoCloseable {
        private final HikariDataSource pool;
        private final JdbcProcessingHistoryRepository repository;
        private final boolean inspection;
        private boolean ready;
        private long retryAfter;
        HistoryStore(String url,String user,String password,boolean inspection) {
            this.inspection=inspection;
            if(url.isBlank()){pool=null;repository=null;return;}
            var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);config.setPoolName("processing-history");
            config.setMaximumPoolSize(4);config.setMinimumIdle(0);config.setConnectionTimeout(1000);config.setValidationTimeout(1000);config.setInitializationFailTimeout(-1);config.setReadOnly(inspection);
            config.addDataSourceProperty("connectTimeout","1");config.addDataSourceProperty("socketTimeout","3");pool=new HikariDataSource(config);repository=new JdbcProcessingHistoryRepository(pool);
        }
        public boolean enabled(){return pool!=null;}
        private synchronized void initialize() {
            if(!enabled())throw new IllegalStateException("History is disabled");if(ready)return;
            if(System.nanoTime()<retryAfter)throw new IllegalStateException("History reconnect is pending");
            try {
                var flyway=Flyway.configure().dataSource(pool).defaultSchema("edgeai_history").schemas("edgeai_history").locations("classpath:db/history-migration").cleanDisabled(true).load();
                if(inspection)flyway.validate();else flyway.migrate();ready=true;
            }catch(RuntimeException e){retryAfter=System.nanoTime()+5_000_000_000L;throw e;}
        }
        public void append(List<Sample> samples){if(inspection)throw new IllegalStateException("Inspection is read-only");initialize();repository.append(samples);}
        public Map<String,Object> read(UUID id,String output,Instant from,Instant to,int limit,String cursor,Integer bucket){initialize();return repository.read(id,output,from,to,limit,cursor,bucket);}
        public int maintain(Instant now){if(inspection)return 0;initialize();return repository.maintain(now);}
        public void close(){if(pool!=null)pool.close();}
    }
}
