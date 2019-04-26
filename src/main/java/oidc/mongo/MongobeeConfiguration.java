package oidc.mongo;

import com.github.mongobee.Mongobee;
import com.github.mongobee.changeset.ChangeLog;
import com.github.mongobee.changeset.ChangeSet;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.config.AbstractMongoConfiguration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ChangeLog
public class MongobeeConfiguration extends AbstractMongoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MongobeeConfiguration.class);

    @Value("${spring.data.mongodb.uri}")
    private String uri;

    @Value("${mongodb_db}")
    private String databaseName;

    @Override
    public MongoClient mongoClient() {
        return new MongoClient(new MongoClientURI(uri));
    }

    @Override
    protected String getDatabaseName() {
        return databaseName;
    }

    @Bean
    public Mongobee mongobee(@Value("${spring.data.mongodb.uri}") String uri) throws Exception {
        Mongobee runner = new Mongobee(uri);
        runner.setChangeLogsScanPackage("oidc.mongo");
        runner.setDbName(getDatabaseName());
        runner.setMongoTemplate(mongoTemplate());
        return runner;
    }

    @ChangeSet(order = "001", id = "createIndexes", author = "Okke Harsta")
    public void createCollections(MongoTemplate mongoTemplate) {
        Map<String, List<String>> indexInfo = new HashMap<>();
        indexInfo.put("access_tokens", Arrays.asList("value"));
        indexInfo.put("authorization_codes", Arrays.asList("code"));
        indexInfo.put("users", Arrays.asList("sub"));
        indexInfo.forEach((collection, fields) -> {
            if (!mongoTemplate.collectionExists(collection)) {
                mongoTemplate.createCollection(collection);
            }
            fields.forEach(field -> {
                IndexOperations indexOperations = mongoTemplate.indexOps(collection);
                indexOperations.ensureIndex(new Index(field, Sort.Direction.ASC).named(String.format("%s_unique", field)).unique());
            });

        });
    }
}
