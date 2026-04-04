# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IDE Integration

This project is open in JetBrains IDE. Use the `mcp__jetbrains__*` tools to read files, search code, navigate the project, build, and run tasks instead of plain filesystem tools where possible.

## Full Guidelines

**AGENTS.md** at the repo root contains the complete development reference: code style, naming conventions, architecture patterns, test patterns, and proto/gRPC rules. Read it before making non-trivial changes.

## Build Commands

```bash
# Windows
.\gradlew.bat build                  # Build everything (includes agent fat JAR)
.\gradlew.bat :composeApp:run        # Run the desktop app
.\gradlew.bat :agent:agentJar        # Build agent fat JAR only
.\gradlew.bat :sample:jar            # Build sample target app
.\gradlew.bat :sample:obfuscate      # Build obfuscated sample + mapping.txt

# Test
.\gradlew.bat test                                      # All modules
.\gradlew.bat :agent:test                               # Agent only
.\gradlew.bat :core:test                                # Core only
.\gradlew.bat :composeApp:jvmTest                       # composeApp only (note: jvmTest, not test)
.\gradlew.bat :composeApp:jvmTest --tests "*.FooTest"   # Single test class
.\gradlew.bat :agent:test --tests "*.FooTest.methodName" # Single test method
```

## Architecture

Bytesight is a JVM reverse-engineering desktop tool. The user attaches it to a running JVM process; the agent instruments that process and streams data back to the UI over gRPC.

```
composeApp  ──(gRPC)──►  agent (injected into target JVM)
    │                        │
    │                    ByteBuddy instrumentation
    │                    ClassCollector, HookManager
    │
    ├── protocol   (shared proto stubs — never edit generated code, modify .proto and rebuild)
    └── core       (pure Kotlin: Vineflower decompiler, ASM-based analysis)
```

**Module rules:**
- `agent` is **pure Java** (no Kotlin) to keep the fat JAR small.
- `composeApp` depends on `protocol` and `core` — **never** on `agent`.
- `core` has no UI dependency — pure analysis logic.

**UI layer (composeApp):**
- MVVM: each screen has a `*ViewModel` exposing `StateFlow<*UiState>` and a `*Screen` composable.
- DI via Koin — all wiring in `di/AppModule.kt`.
- Navigation state in `ui/navigation/Navigation.kt` (`Screen` enum + `NavigationState`).
- New screens require: add `Screen` enum entry, `NavigationRailItem` in `Sidebar.kt`, `when` branch in main nav composable, ViewModel registration in `AppModule.kt`.
- `AgentClient` is the single gRPC gateway; `AttachService` wraps the JVM Attach API.

**Data flow for a new feature:**
1. Define RPC in `protocol/src/main/proto/bytesight.proto` → rebuild to regenerate stubs.
2. Implement server-side in `agent/.../BytesightAgentService.java`.
3. Add client method to `AgentClient.kt`.
4. Add analysis logic in `core` (using ASM for bytecode work).
5. Wire ViewModel → Screen in `composeApp`.

**Test locations:**
- `agent`, `core`, `sample`: `src/test/java/...` — standard `test` task.
- `composeApp`: `src/jvmTest/kotlin/...` — `jvmTest` task.
- Integration tests in `composeApp` are gated by `@EnabledIf("isIntegrationTestEnabled")` and auto-depend on agent + sample JARs.

## Key Files

| File | Purpose |
|------|---------|
| `protocol/src/main/proto/bytesight.proto` | gRPC service + message definitions |
| `composeApp/.../di/AppModule.kt` | Koin DI wiring |
| `composeApp/.../navigation/Navigation.kt` | Screen enum + navigation state |
| `composeApp/.../navigation/Sidebar.kt` | Navigation rail |
| `agent/.../BytesightAgent.java` | Agent entry point (`premain`) |
| `agent/.../server/BytesightAgentService.java` | gRPC service implementation |
| `core/.../decompiler/VineflowerDecompiler.kt` | Vineflower wrapper |
| `gradle/libs.versions.toml` | Dependency version catalog |
| `devdocs/plan.md` | Feature roadmap (7 planned features across 4 phases) |
