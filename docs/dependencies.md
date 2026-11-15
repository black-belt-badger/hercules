# Dependency policy and consumer footprint

Hercules is a thin Java 17 library JAR. It does not shade, bundle or relocate
third-party classes. Dependencies have explicit Maven scopes, and adding a
runtime library is an API and operational decision rather than a convenience.

## Direct dependencies (0.1.0-SNAPSHOT)

| Library | Scope | Role and consumer impact |
| --- | --- | --- |
| Guava 33.7.2-jre | `compile` | **Required by the implemented recursion-schemes API [#14](https://github.com/black-belt-badger/hercules/issues/14)**. `Tree` and `Layer` publicly expose Guava `ImmutableList`, preserving the original prototype's child-slot contract. This is a deliberate part of the public Java API, and Maven consumers inherit Guava transitively. |
| JSpecify 1.0.1 | `compile` | `@NullMarked` and `@Nullable` are part of the published static nullness contract. Keep annotations accessible to downstream Java analyzers; they do not perform checks at runtime. |
| Lombok 1.18.48 | `provided` and annotation processor | Generates fail-fast structural null checks **while building Hercules**. Compiled consumer code runs without Lombok or its annotation processor. No Lombok-specific API types are required. |
| JUnit Jupiter 5.14.4 | `test` | Law and behavior tests only; not propagated to consumers. |
| Instancio JUnit 5.6.0 | `test` | Generated/randomized law tests only; not propagated to consumers. |

Error Prone/NullAway and Maven plugins are **build tools**, not declared
application dependencies. See [nullness.md](nullness.md) for their contracts.

## Guava and the morphism boundary

Issue #14 extracts `ana`, `cata`, `hylo`, `Tree`, `Layer` and
optional `Trees.prettyPrint`. The model intentionally exposes
`ImmutableList` in its public record components rather than wrapping
it in a `List` adapter. **Guava is now a real consumer-visible API
dependency**, not a reserved future import. Keep its Maven compile scope.

The library still prefers JDK `Function`, `BiFunction` and other standard
types at unrelated API boundaries. This dependency was deliberately
reserved before extraction, not an invitation to use Guava everywhere.

## Consumer dependency tree

The current `./mvnw dependency:tree` includes these compile-scope
artifacts (plus Lombok as provided and test-only libraries):

- `guava`, plus `failureaccess`, the empty `listenablefuture` artifact,
  `error_prone_annotations`, `j2objc-annotations` and JSpecify metadata
- `jspecify` as a direct dependency (also referenced transitively by Guava)

Those transitive compile dependencies matter to consumers even though
only the morphism API currently requires Guava at runtime. We should not
describe the present artifact as having zero transitive dependencies.

## Verification and acceptance criteria

Use `./scripts/verify-consumer-deps.sh` after `./mvnw verify`. It
installs the local snapshot, compiles a throwaway Maven consumer with
**only Hercules declared**, asserts expected transitive scopes, rejects
accidental Lombok/JUnit/Instancio leakage, and executes a basic public API
example with only the consumer classes and Hercules JAR on the runtime
classpath. It also verifies the JAR contains no bundled third-party
packages. The installed snapshot is local, not a public release.

For any new dependency, require evidence that a Java 17 JDK-only design
is insufficient; state whether it enters public method/record signatures;
justify compile/runtime/provided/test scope, transitive size, security,
versioning and runtime semantics; prefer optional adapters for
integration-specific dependencies; and validate an external consumer.
Do not silently add a mandatory framework or require Lombok in applications.
