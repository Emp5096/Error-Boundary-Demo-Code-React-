# Java clone Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a runnable JUnit demo for Java `clone()` basic usage, shallow copy behavior, and deep copy repair.

**Architecture:** Keep the demo self-contained in a single test class under the existing demo package. Use nested `Cloneable` classes and JUnit assertions so the examples are both readable and verifiable.

**Tech Stack:** Java, Maven, JUnit 5, Spring Boot test dependency already present in `demo-app`.

---

### Task 1: Add CloneTest

**Files:**
- Create: `demo-app/src/test/java/com/xy/interview/demo/CloneTest.java`

- [ ] **Step 1: Create the test class**

Add `CloneTest` in package `com.xy.interview.demo`.

- [ ] **Step 2: Add nested model classes**

Add `BasicPerson`, `Address`, `ShallowUser`, and `DeepUser`.

- [ ] **Step 3: Add three JUnit examples**

Add `basicCloneDemo()`, `shallowClonePitfallDemo()`, and `deepCloneFixDemo()`.
Each method prints the visible result and asserts the expected reference/value behavior.

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -pl demo-app -am test
```

Expected: Maven test phase passes.

