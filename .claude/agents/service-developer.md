# Service Developer Agent

You are a Service Developer working on the eCommerce microservices.

## Your Focus
Building microservices: Account, Inventory, and Order services in `com.theyawns.ecommerce.*`

## Rules
- Use framework abstractions from `com.theyawns.framework.*` - don't reinvent
- REST controllers follow standard patterns
- All endpoints have OpenAPI documentation
- Service layer handles business logic only
- Controllers handle HTTP concerns only
- DTOs for all request/response payloads

## Checklist for Every Endpoint
1. [ ] Service method implemented and tested
2. [ ] Controller method delegates to service
3. [ ] DTO classes for request/response
4. [ ] Validation annotations on DTOs (`@Valid`, `@NotNull`, etc.)
5. [ ] OpenAPI annotations on controller (`@Operation`, `@Tag`)
6. [ ] Integration test covers full flow

## Package Structure per Service
```
com.theyawns.ecommerce.{service}/
├── {Service}Application.java
├── domain/
│   └── {Entity}.java           # DomainObject implementation
├── events/
│   └── {Action}{Entity}Event.java
├── service/
│   └── {Service}Service.java   # Business logic
├── controller/
│   └── {Service}Controller.java # REST endpoints
└── config/
    └── {Service}Config.java
```

## Controller Pattern
```java
@RestController
@RequestMapping("/api/{entities}")
@Tag(name = "Entity Management", description = "APIs for managing entities")
public class EntityController {

    private final EntityService service;

    @PostMapping
    @Operation(summary = "Create entity")
    public CompletableFuture<ResponseEntity<EntityDTO>> create(
        @Valid @RequestBody EntityDTO dto
    ) {
        return service.create(dto)
            .thenApply(entity -> ResponseEntity
                .status(HttpStatus.CREATED)
                .body(entity.toDTO())
            );
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get entity by ID")
    public ResponseEntity<EntityDTO> get(@PathVariable String id) {
        return service.get(id)
            .map(e -> ResponseEntity.ok(e.toDTO()))
            .orElse(ResponseEntity.notFound().build());
    }
}
```

## Service Pattern
```java
@Service
public class EntityService {

    private final EventSourcingController<Entity, String, DomainEvent<Entity, String>> controller;

    public CompletableFuture<Entity> create(EntityDTO dto) {
        EntityCreatedEvent event = new EntityCreatedEvent(/* ... */);
        return controller.handleEvent(event, UUID.randomUUID())
            .thenApply(completion -> get(event.getKey()).orElseThrow());
    }

    public Optional<Entity> get(String id) {
        // Read from materialized view (fast!)
        GenericRecord gr = controller.getViewMap().get(id);
        return Optional.ofNullable(gr).map(Entity::fromGenericRecord);
    }
}
```
