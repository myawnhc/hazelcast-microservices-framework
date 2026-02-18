package com.theyawns.framework.bench;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.event.DomainEvent;

import java.time.Instant;

/**
 * Self-contained event fixture for JMH benchmarks.
 * Has 14 fields in toGenericRecord() — matching CustomerCreatedEvent's field count —
 * to exercise identical GenericRecordBuilder patterns without depending on ecommerce-common.
 *
 * <p>Fields (14 total):
 * <ul>
 *   <li>7 base metadata: eventId, eventType, eventVersion, source, timestamp, key, correlationId</li>
 *   <li>3 saga fields: sagaId, sagaType, stepNumber (isCompensating folded into boolean)</li>
 *   <li>1 boolean: isCompensating</li>
 *   <li>3 domain-specific: name, category, description</li>
 * </ul>
 */
public class BenchmarkEvent extends DomainEvent<BenchmarkDomainObj, String> {

    public static final String SCHEMA_NAME = "BenchmarkEvent";
    public static final String EVENT_TYPE = "BenchmarkCreated";

    private String name;
    private String category;
    private String description;

    public BenchmarkEvent() {
        super();
        this.eventType = EVENT_TYPE;
    }

    public BenchmarkEvent(String key, String name, String category, String description) {
        super(key);
        this.eventType = EVENT_TYPE;
        this.source = "benchmark-service";
        this.correlationId = "bench-corr-" + key;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    @Override
    public GenericRecord toGenericRecord() {
        return GenericRecordBuilder.compact(SCHEMA_NAME)
                .setString("eventId", eventId)
                .setString("eventType", eventType)
                .setString("eventVersion", eventVersion)
                .setString("source", source)
                .setInt64("timestamp", timestamp != null ? timestamp.toEpochMilli() : 0)
                .setString("key", key)
                .setString("correlationId", correlationId)
                .setString("sagaId", sagaId)
                .setString("sagaType", sagaType)
                .setInt32("stepNumber", stepNumber != null ? stepNumber : 0)
                .setBoolean("isCompensating", isCompensating != null && isCompensating)
                .setString("name", name)
                .setString("category", category)
                .setString("description", description)
                .build();
    }

    public static BenchmarkEvent fromGenericRecord(GenericRecord record) {
        if (record == null) {
            return null;
        }
        BenchmarkEvent event = new BenchmarkEvent();
        event.eventId = record.getString("eventId");
        event.eventType = record.getString("eventType");
        event.eventVersion = record.getString("eventVersion");
        event.source = record.getString("source");
        event.key = record.getString("key");
        event.correlationId = record.getString("correlationId");

        Long timestampMs = record.getInt64("timestamp");
        event.timestamp = timestampMs != null && timestampMs != 0 ? Instant.ofEpochMilli(timestampMs) : null;

        event.sagaId = record.getString("sagaId");
        event.sagaType = record.getString("sagaType");
        Integer step = record.getNullableInt32("stepNumber");
        event.stepNumber = step;
        event.isCompensating = record.getBoolean("isCompensating");

        event.name = record.getString("name");
        event.category = record.getString("category");
        event.description = record.getString("description");

        return event;
    }

    @Override
    public GenericRecord apply(GenericRecord domainObjectRecord) {
        Instant now = Instant.now();
        return GenericRecordBuilder.compact(BenchmarkDomainObj.SCHEMA_NAME)
                .setString("id", key)
                .setString("name", name)
                .setString("category", category)
                .setString("description", description)
                .setString("status", "ACTIVE")
                .setInt64("updatedAt", now.toEpochMilli())
                .build();
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
}
