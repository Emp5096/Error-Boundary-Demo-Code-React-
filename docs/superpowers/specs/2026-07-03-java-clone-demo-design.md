# Java clone Demo Design

## Goal

Add a small JUnit demo that explains Java `Object.clone()` through three runnable examples:
basic clone usage, the shallow-copy pitfall, and a deep-copy fix.

## Location

Create `demo-app/src/test/java/com/xy/interview/demo/CloneTest.java`.

## Behavior

The demo uses nested classes inside `CloneTest` so the example is self-contained.
It prints the key observations and uses JUnit assertions to make each conclusion
executable:

- Basic clone: the cloned object is a different reference, while scalar fields match.
- Shallow clone: the outer object is copied, but its nested `Address` reference is shared.
- Deep clone: the outer object and nested `Address` are both copied, so nested mutations do not affect the original.

## Testing

Run `mvn -pl demo-app -am test` from the repository root.

