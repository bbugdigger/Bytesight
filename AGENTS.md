# AGENTS.md — Bytesight

Bytesight is a JVM reverse-engineering desktop application. It attaches a Java agent to
a running JVM process, inspects loaded classes, decompiles bytecode (Vineflower), and
traces method calls in real time via gRPC streaming.

## Project layout

| Module | Language | Purpose |
|--------------|----------|--------------------------------------------------|
| `composeApp` | Kotlin | Desktop UI — Compose Multiplatform, MVVM + Koin |
| `agent` | Java | Java agent — ByteBuddy instrumentation, gRPC server |
| `protocol` | Kotlin | gRPC/Protobuf definitions and generated stubs |
| `core` | Kotlin | Decompiler wrapper (Vineflower) |
| `sample` | Java | Sample target app for testing/instrumentation |

All modules target **JVM 17**. Build system is **Gradle 8.14.3** with Kotlin DSL.

## Build commands

```bash
# Build everything (includes agent fat JAR)
./gradlew build

# Run the desktop application
./gradlew :composeApp:run

# Build the agent fat JAR only
./gradlew :agent:agentJar

# Build the sample app (normal / obfuscated)
./gradlew :sample:jar
./gradlew :sample:obfuscate
```

On Windows use `.\gradlew.bat` instead of `./gradlew`.

## Test commands

```bash
# Run ALL tests across every module
./gradlew test

# Run tests for a single module
./gradlew :agent:test
./gradlew :composeApp:test
./gradlew :core:test

# Run a single test class
./gradlew :agent:test --tests "com.bugdigger.agent.hook.TraceEventBufferTest"
./gradlew :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.trace.TraceViewModelTest"
./gradlew :core:test --tests "com.bugdigger.core.decompiler.VineflowerDecompilerTest"

# Run a single test method (Kotlin backtick names need quoting)
./gradlew :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.trace.TraceViewModelTest.Initial State.should have empty initial state"

# Run a single test method (Java camelCase names)
./gradlew :agent:test --tests "com.bugdigger.agent.hook.TraceEventBufferTest.addListener_receivesEvents"
```

Note: `composeApp` uses the `jvmTest` task (Kotlin Multiplatform), while `agent`, `core`,
and `sample` use the standard `test` task.

Integration tests in `composeApp` are gated by `@EnabledIf("isIntegrationTestEnabled")` and
require the agent JAR + sample JAR to be pre-built (both are wired as task dependencies).

## Lint / format

There is no dedicated linter or formatter configured (no ESLint, Prettier, Checkstyle,
ktlint, or Detekt). The project follows **Kotlin official code style**
(`kotlin.code.style=official` in `gradle.properties`). Use your IDE's default Kotlin/Java
formatter.

## Code style guidelines

### Naming conventions

| Element | Convention | Example |
|----------------------|----------------------|-----------------------------------------|
| Packages | lowercase dotted | `com.bugdigger.agent.hook` |
| Classes / Interfaces | PascalCase | `TraceViewModel`, `AgentClient` |
| Functions / methods | camelCase | `refreshClasses`, `addHook` |
| Constants | UPPER_SNAKE_CASE | `DEFAULT_PORT`, `AGENT_VERSION` |
| Variables / props | camelCase | `connectionKey`, `uiState` |
| Enum values | UPPER_SNAKE_CASE | `LOG_ENTRY_EXIT`, `ENTRY` |
| Files | PascalCase (match class) | `TraceViewModel.kt`, `HookManager.java` |
| Test classes | `ClassNameTest` suffix | `TraceViewModelTest`, `HookResultTest` |
| Kotlin test methods | Backtick descriptive | `` `should have empty initial state` `` |
| Java test methods | snake_case descriptive | `success_createsSuccessfulResult` |

### Imports

- Prefer **named imports**; avoid wildcards except for Compose UI packages
  (`androidx.compose.runtime.*`, `androidx.compose.foundation.layout.*`).
- Use static imports for JUnit assertions in Java: `import static org.junit.jupiter.api.Assertions.*`.
- Order: stdlib/JDK → third-party libs → project modules. No path aliases.

### Types

- **Kotlin**: Use `data class` for state/DTOs, `sealed class` for discriminated unions,
  `interface` for abstractions, `enum class` for fixed sets.
- Explicit types on public API (function signatures, public properties); inferred types for
  local variables.
- Use `StateFlow<T>` / `MutableStateFlow<T>` for reactive UI state.
- Nullable types used judiciously (`String?`, `ClassInfo?`) — avoid unnecessary nullability.
- **Java**: Standard classes for models; prefer inner `static class` for implementation details.
  Use `ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicLong` for thread safety.

### Error handling

- **Kotlin**: Use `Result<T>` with `runCatching { }` + `.onSuccess` / `.onFailure` chaining.
  Use `sealed class` results for domain-specific outcomes (e.g., `DecompilationResult.Success`,
  `DecompilationResult.Failure`).
- **Java**: Try-catch with SLF4J logging (`logger.error(...)`) and wrapping in
  `RuntimeException` for fatal errors. Custom Result types via static factory methods
  (`HookResult.success()`, `HookResult.failure()`).
- **UI errors**: Store in `error: String?` field of UI state data classes, display via
  `ErrorBanner` composable, clear via `clearError()`.

### Architecture patterns

- **MVVM** for Compose UI: `ViewModel` exposes `StateFlow<UiState>` consumed by
  `@Composable` screens.
- **Dependency injection**: Koin — `singleOf`, `factoryOf`,
  `single<Interface> { Implementation() }` declared in `AppModule.kt`.
- **Service layer**: `AgentClient` (gRPC client), `AttachService` (JVM Attach API).
- **Agent**: Java Instrumentation API → `ClassFileTransformer` + ByteBuddy `Advice` for
  method interception. Embedded gRPC server for communication with the desktop app.

### Comments and documentation

- Use KDoc (`/** ... */`) on public classes, interfaces, and methods.
- Use Javadoc with `@param`, `@return`, `@throws` tags in Java code.
- Inline comments explain *why*, not *what*.
- Use section dividers for long files: `// ========== Helper Methods ==========`.

### Test patterns

- **JUnit 5** across all modules with `useJUnitPlatform()`.
- **Kotlin tests**: MockK (`mockk(relaxed = true)`), `kotlinx-coroutines-test` (`runBlocking`).
- **Java tests**: JUnit 5 assertions, `CountDownLatch` for async verification.
- Group related tests with `@Nested` inner classes and `@DisplayName`.
- Follow **Arrange-Act-Assert** pattern consistently.
- Test file location mirrors source: `src/test/java/...` (agent, core) or
  `src/jvmTest/kotlin/...` (composeApp).

### Key dependencies

| Dependency | Purpose |
|-----------------------------|-----------------------------------------------|
| Jetpack Compose Multiplatform | Desktop UI framework |
| Koin | Dependency injection |
| gRPC + Protobuf | Agent ↔ UI communication |
| ByteBuddy | Runtime bytecode instrumentation |
| Vineflower | Bytecode decompilation |
| RSyntaxTextArea | Code editor component in UI |
| SLF4J + Logback | Logging |
| JUnit 5 + MockK | Testing |

### Proto / gRPC

Protocol definitions live in `protocol/src/main/proto/bytesight.proto`. Code is generated
by the Protobuf Gradle plugin into `protocol/build/generated/source/proto/`. Never edit
generated files — modify the `.proto` file and rebuild.

### General rules

- Target JVM 17 — do not use APIs from newer JDK versions.
- Keep modules decoupled: UI depends on `protocol` and `core`, never on `agent`.
- The `agent` module is pure Java (no Kotlin) to minimize the fat JAR size.
- Prefer extension functions for display helpers (e.g., `MethodTraceEvent.toDisplay()`).
- Use `private` for composables that are internal to a screen file.
- Visibility: omit `public` (Kotlin default); use `internal` or `private` deliberately.
