# Implement Plan

1. Read the latest plan file from `docs/plans/` for the current day/phase
2. Implement all deliverables listed in the plan
3. After each module change, run `mvn install -pl <module>` to update local repo
4. Run full test suite: `mvn clean test` from project root
5. If all tests pass, commit with message format: "Phase X Day Y: <description>"
6. If tests fail, fix issues and re-run before committing
