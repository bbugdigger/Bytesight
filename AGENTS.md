# AGENTS.md - Bytesight Project Guidelines

This document provides guidelines for AI coding agents working in this repository.

## Project Overview

Bytesight is a **Kotlin Multiplatform** project targeting Desktop (JVM) using **Compose Multiplatform**.

### Project Structure

```
bytesight/
├── composeApp/          # Main Compose Multiplatform application
│   └── src/jvmMain/     # JVM-specific Kotlin code
├── agent/               # Java module (JUnit 5 tests)
├── protocol/            # Java module (JUnit 5 tests)
├── sample/              # Java module (JUnit 5 tests)
└── gradle/              # Gradle wrapper and version catalog
```

### Tech Stack
- **Kotlin**: 2.3.0 with official code style
- **Compose Multiplatform**: 1.10.0
- **Java**: For agent/protocol/sample modules
- **Build System**: Gradle 8.x with Kotlin DSL
- **Testing**: JUnit 5 (Java modules), kotlin-test (Kotlin modules)

---

## JetBrains IDE MCP - MANDATORY for Project Files and Operations

**NEVER use these tools:** `Grep`, `Glob`, `Read`, `Edit`, `Write`, `Task(Explore)`.  
**ALWAYS use JetBrains MCP equivalents instead.**

**Exception:** For paths outside the project (e.g., `~/.claude/`), use standard tools — MCP only works with project-relative paths.

**NEVER use `execute_terminal_command` tool.**  
**ALWAYS use default `Bash` instead.**

Use other similar tools only if it is not possible to use the JetBrains IDE MCP, and you together with the user can't manage to make it work.

### Why MCP over Standard Tools?

**Synchronization with IDE:**
- Standard tools work with the filesystem directly, MCP works with IDE's view of files
- If a file is open in IDE with unsaved changes, standard `Read` sees the old disk version, while MCP sees current IDE buffer
- Standard `Write`/`Edit` may conflict with IDE's buffer or not be picked up immediately
- MCP changes integrate with IDE's undo history

**IDE capabilities:**
- `search_in_files_by_text` uses IntelliJ indexes — faster than grep on large codebases
- `rename_refactoring` understands code structure and updates all references correctly
- `get_symbol_info` provides type info, documentation, and declarations
- `get_file_problems` runs IntelliJ inspections beyond syntax checking

### MCP Server Configuration

The JetBrains IDE MCP server can be called as `jetbrains`, `idea`, `my-idea`, `my-idea-dev`, etc.
If there are many options for the JetBrains IDE MCP server, ask the user what MCP server to use.

### Tool Mapping

| Instead of      | Use JetBrains MCP                                     |
|-----------------|-------------------------------------------------------|
| `Read`          | `get_file_text_by_path`                               |
| `Edit`, `Write` | `replace_text_in_file`, `create_new_file`             |
| `Grep`          | `search_in_files_by_text`, `search_in_files_by_regex` |
| `Glob`          | `find_files_by_name_keyword`, `find_files_by_glob`    |
| `Task(Explore)` | `list_directory_tree`, `search_in_files_by_text`      |

### Additional MCP Tools

- **Code analysis**: `get_symbol_info`, `get_file_problems` for understanding code
- **Refactoring**: `rename_refactoring` for symbol renaming (safer than text replacement)
- **Run configs**: `get_run_configurations()` to discover, `execute_run_configuration(name="...")` to run

### MANDATORY - Verify After Writing Code

Use JetBrains MCP `get_file_problems` with `errorsOnly=false` to check files for warnings. FIX any warnings related to the code changes made. You may ignore unrelated warnings.

---

## Build/Lint/Test Commands

### Build Commands
```bash
# Build entire project
./gradlew build

# Build specific module
./gradlew :composeApp:build
./gradlew :agent:build
./gradlew :protocol:build

# Clean build
./gradlew clean build
```

### Run Commands
```bash
# Run the desktop application
./gradlew :composeApp:run

# Or use IDE run configuration: "composeApp [jvm]"
```

### Test Commands
```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :composeApp:jvmTest
./gradlew :agent:test
./gradlew :protocol:test
./gradlew :sample:test

# Run a single test class (JUnit 5 - Java modules)
./gradlew :agent:test --tests "com.bugdigger.MyTestClass"

# Run a single test method
./gradlew :agent:test --tests "com.bugdigger.MyTestClass.testMethodName"

# Run tests with pattern matching
./gradlew :agent:test --tests "*MyTest*"
```

### Lint/Check Commands
```bash
# Check project compilation
./gradlew check

# Verify Gradle configuration
./gradlew --dry-run build
```

---

## Code Style Guidelines

### Kotlin Code Style

This project uses **official Kotlin code style** (`kotlin.code.style=official` in gradle.properties).

**Formatting:**
- 4-space indentation (no tabs)
- Opening braces on same line
- Trailing commas in multiline constructs

**Imports:**
- Group imports: standard library, third-party, project imports
- Wildcard imports allowed for common packages (e.g., `androidx.compose.runtime.*`)
- Sort imports alphabetically within groups

**Naming Conventions:**
- Classes/Interfaces: `PascalCase` (e.g., `Greeting`, `JVMPlatform`)
- Functions/Properties: `camelCase` (e.g., `greet()`, `getPlatform()`)
- Constants: `SCREAMING_SNAKE_CASE` or `camelCase` for top-level vals
- Packages: lowercase, dot-separated (e.g., `com.bugdigger.bytesight`)
- Files: Match primary class name (e.g., `Greeting.kt` for `class Greeting`)

**Types:**
- Prefer explicit types for public API, inferred types for local variables
- Use nullable types (`Type?`) explicitly; avoid `!!` operator
- Prefer `val` over `var` when possible

### Java Code Style

**Formatting:**
- 4-space indentation
- Opening braces on same line
- One class per file

**Naming Conventions:**
- Classes: `PascalCase`
- Methods/Variables: `camelCase`
- Constants: `SCREAMING_SNAKE_CASE`
- Packages: lowercase (e.g., `com.bugdigger`)

### Compose-Specific Guidelines

**Composable Functions:**
- Name composables as nouns/noun phrases (`App`, `Greeting`, not `ShowApp`)
- Annotate with `@Composable`
- Use `@Preview` for preview functions
- State hoisting: lift state up when possible

**Modifiers:**
- Chain modifiers in order: layout → behavior → visual
- Example: `.fillMaxSize().clickable().background()`

### Error Handling

- Use Kotlin's `Result` type or sealed classes for error modeling
- Avoid silent failures; log or propagate errors appropriately
- In Compose, handle errors gracefully in UI state

### File Organization

```
src/
├── jvmMain/kotlin/com/bugdigger/bytesight/
│   ├── App.kt           # Main composable
│   ├── main.kt          # Entry point
│   ├── Platform.kt      # Platform abstractions
│   └── [feature]/       # Feature-specific code
└── jvmTest/kotlin/      # Tests mirror main structure
```

---

## Dependencies

Managed via Gradle Version Catalog (`gradle/libs.versions.toml`):
- Reference libraries: `libs.compose.material3`
- Reference plugins: `libs.plugins.kotlinMultiplatform`

When adding dependencies, update the version catalog first, then reference in build.gradle.kts.

---

## IDE Integration

- **Hot Reload**: Compose Hot Reload plugin enabled for rapid UI development
- **Run Configuration**: Use "composeApp [jvm]" for running the desktop app
- After code changes, use `get_file_problems` to verify no new issues introduced
