package com.theyawns.framework.bench;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.domain.DomainObject;

import java.time.Instant;

/**
 * Self-contained domain object fixture for JMH benchmarks.
 * Exercises the same GenericRecordBuilder patterns as production domain objects
 * (6 fields matching typical domain object complexity) without depending on
 * ecommerce-common to avoid circular dependencies.
 */
public class BenchmarkDomainObj implements DomainObject<String> {

    public static final String SCHEMA_NAME = "BenchmarkDomainObj";

    private final String id;
    private final String name;
    private final String category;
    private final String description;
    private final String status;
    private final long updatedAt;

    public BenchmarkDomainObj(String id, String name, String category,
                              String description, String status) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.status = status;
        this.updatedAt = Instant.now().toEpochMilli();
    }

    private BenchmarkDomainObj(String id, String name, String category,
                               String description, String status, long updatedAt) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    @Override
    public String getKey() {
        return id;
    }

    @Override
    public GenericRecord toGenericRecord() {
        return GenericRecordBuilder.compact(SCHEMA_NAME)
                .setString("id", id)
                .setString("name", name)
                .setString("category", category)
                .setString("description", description)
                .setString("status", status)
                .setInt64("updatedAt", updatedAt)
                .build();
    }

    public static BenchmarkDomainObj fromGenericRecord(GenericRecord record) {
        return new BenchmarkDomainObj(
                record.getString("id"),
                record.getString("name"),
                record.getString("category"),
                record.getString("description"),
                record.getString("status"),
                record.getInt64("updatedAt")
        );
    }

    @Override
    public String getSchemaName() {
        return SCHEMA_NAME;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
