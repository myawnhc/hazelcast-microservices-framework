# Day 16: Integration Testing for Vector Store (Phase 2)

## Context

Day 15 (Vector Store Integration) is complete and committed (`3be86e8`). All unit tests pass. Day 16 adds integration-level tests for the auto-configuration and demo scripts to exercise the similar products endpoint end-to-end.

**Goal**: Verify `VectorStoreAutoConfiguration` wiring via `ApplicationContextRunner`, add a demo scenario for the similar products endpoint, and update sample data scripts.

---

## Deliverables (3 files: 1 new, 2 modified)

### 1. NEW: `VectorStoreAutoConfigurationTest.java`

**Path**: `framework-core/src/test/java/com/theyawns/framework/vectorstore/VectorStoreAutoConfigurationTest.java`

**Pattern**: Follows `EditionAutoConfigurationTest` exactly — `ApplicationContextRunner` + `AutoConfigurations.of(...)`.

**6 test cases in 3 nested classes:**

**`WithHazelcast`** (4 tests):
1. `shouldEnableVectorStoreProperties` — asserts `VectorStoreProperties` and `VectorStoreService` beans exist
2. `shouldCreateNoOpFallbackWhenNoOtherServiceExists` — asserts fallback is `NoOpVectorStoreService` with `isAvailable() == false`
3. `shouldBindDefaultProperties` — asserts `collectionName="product-vectors"`, `dimension=128`, `maxConnections=16`, `efConstruction=200`
4. `shouldBindCustomProperties` — uses `withPropertyValues(...)` to override all four fields, asserts custom values

**`WithoutHazelcast`** (1 test):
5. `shouldNotCreateAnyBeansWhenHazelcastMissing` — asserts no `VectorStoreProperties` or `VectorStoreService` beans

**`CustomBeanOverride`** (1 test):
6. `shouldNotOverrideExistingVectorStoreServiceBean` — user-provided `VectorStoreService` wins over auto-config fallback

**Inner configs:**
- `HazelcastConfig` — mock `HazelcastInstance` with real `Config()` (same pattern as `EditionAutoConfigurationTest`)
- `CustomVectorStoreConfig` — provides custom `VectorStoreService` bean (subclass of `NoOpVectorStoreService` with custom `getImplementationType()`)

**Note**: No `HZ_LICENSEKEY` override needed — this test only tests `VectorStoreAutoConfiguration` in isolation, not `EditionAutoConfiguration`.

---

### 2. MODIFY: `scripts/demo-scenarios.sh`

Add **Scenario 4: Similar Products (Vector Store)** and update menu/case statements.

**New function `scenario_4_similar_products()`** (insert before `show_menu`):
- Step 1: Use `LAPTOP_ID` from sample data (or create a product on the fly if not set)
- Step 2: Fetch and display product details
- Step 3: Call `GET /api/products/{id}/similar?limit=5`, parse `vectorStoreAvailable`, `implementation`, `message`
- Step 4: Display results — if Enterprise, show similar products; if Community, show informational message with upgrade instructions

**Menu updates:**
- `show_menu()`: Add `4) Similar Products (Vector Store)` entry, change prompt to `[1-4, all, q]`
- Command-line case (line ~656): Add `4) scenario_4_similar_products ;;`
- Interactive case (line ~682): Add `4) scenario_4_similar_products ;;`
- `all)` blocks: Add `scenario_4_similar_products` after scenario 3
- Error message: Update to `"1, 2, 3, 4, all, or q"`
- Usage line: Update to `$0 [1|2|3|4|all]`

---

### 3. MODIFY: `scripts/load-sample-data.sh`

**One-line addition** at line 271 (after "View product" line):
```
echo "  4. Similar products: curl $INVENTORY_SERVICE/api/products/$LAPTOP_ID/similar"
```

---

## Implementation Order

1. Create `VectorStoreAutoConfigurationTest.java`
2. Run `mvn clean test -pl framework-core` — verify all tests pass
3. Add scenario 4 function to `demo-scenarios.sh`
4. Update menu and case statements in `demo-scenarios.sh`
5. Add similar products line to `load-sample-data.sh`

---

## Critical Files Reference

| File | Role |
|------|------|
| `framework-core/.../edition/EditionAutoConfigurationTest.java` | Pattern template for the new test |
| `framework-core/.../vectorstore/VectorStoreAutoConfiguration.java` | Class under test |
| `framework-core/.../vectorstore/VectorStoreProperties.java` | Properties to verify binding |
| `framework-core/.../vectorstore/NoOpVectorStoreService.java` | Expected fallback bean |
| `scripts/demo-scenarios.sh` | Add scenario 4 + update menus |
| `scripts/load-sample-data.sh` | Add similar products to "Next Steps" |

---

## Verification

1. `mvn clean test -pl framework-core` — all 600+ tests pass (including new auto-config test)
2. `mvn clean package -DskipTests` — full project builds
3. `bash -n scripts/demo-scenarios.sh` — script syntax valid
4. `bash -n scripts/load-sample-data.sh` — script syntax valid
