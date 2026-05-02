# Multi-source — Step 5: Android Dynamic Attach — Future Design Notes

> **Status: DEFERRED.** This is not a TDD-style implementation plan. During brainstorming the user explicitly chose "APK static analysis only" for Android scope (see Step 2), so dynamic attach to a running Android process is out of scope for the current roadmap. This document exists to capture the architectural shape so a future session can pick it up without re-deriving the design.

## Context

Bytesight today targets the JVM. The agent uses ByteBuddy (JVM bytecode rewriting) and JVMTI (heap inspection, breakpoints, stepping). None of those primitives apply on Android — the runtime is ART, the bytecode format is DEX, and the on-device debugger protocol is JDWP. Adding dynamic Android support is a parallel product, not an extension.

**What was committed in Steps 1–4:** APK static analysis works (Step 2: `ApkClassSource` converts DEX → JVM bytecode via dex-tools and reuses the existing static pipeline). That covers most reverse-engineering use cases that don't require live runtime data.

**What's missing for dynamic:** running on an emulator or device, attaching to a live process, hooking methods, capturing heap state, breakpoint/step debugging.

## Why this is its own product

| Concern | JVM today | Android dynamic |
|---|---|---|
| Bytecode format | `.class` (JVM) | `.dex` (Dalvik) |
| Instrumentation API | `java.lang.instrument` + ByteBuddy | None equivalent. Need Frida (native injection) or Xposed (rooted devices) |
| Debugger primitive | JVMTI (in-process JNI) | JDWP over ADB |
| Heap walk | JVMTI tag map | hprof-android via `am dumpheap`, Android Studio profiler protocol |
| Process discovery | `VirtualMachine.list()` | `adb shell pm list packages` + `adb shell ps` |
| Connection transport | gRPC over localhost | gRPC tunneled through ADB port-forward, OR WebSocket over USB/TCP |

There is **no path** to make ByteBuddy or the existing agent work on ART. Reuse stops at: gRPC stubs (the protocol layer is platform-neutral), the UI tabs (they read from `ClassSource` and `ConnectionRegistry` which we made platform-neutral in Step 1), and the analysis primitives in `core` (operate on JVM bytecode, so any DEX-derived `.class` bytes work).

## Sketch of the architecture (when revisited)

### Module layout

Add a new module: **`agent-android`**. Mirrors `agent`'s gRPC server contract but uses Android primitives.

```
composeApp ──(gRPC same proto)──► agent (JVM)
            ──(gRPC same proto)──► agent-android (ART)  ← NEW
                                       │
                                  Frida (or Xposed) for instrumentation
                                  JDWP for debugger
                                  am dumpheap → hprof for heap
```

Open architectural question: do we deliver the Android agent as a Frida script (works without root, requires the user to install Frida server on the device) or as an Xposed module (cleaner integration, requires root)? Most reverse-engineering targets in practice run on rooted emulators, so Xposed is probably the better default; Frida is a fallback for unrooted real-device testing.

### `ClassSource` impl

Reuses the Step-1 abstraction: `AndroidAgentClassSource` is the runtime-mode Android equivalent of `AgentClassSource`. Capabilities can mirror the JVM agent's (LIVE_TRACE / LIVE_HEAP / LIVE_DEBUG plus STATIC_ANALYSIS), assuming the agent-android module implements the corresponding RPCs. The four migrated VMs from Step 1 (ClassBrowser, Hierarchy, Inspector, Strings) need **zero changes** — they read class metadata + bytecode from any `ClassSource`. The runtime VMs (Trace, Heap, Debugger) **do** need work because their RPCs differ in semantics across runtimes (e.g. JDWP step kinds vs JVMTI step kinds, hprof object layout vs JVMTI tag map).

### Attach flow

`AttachScreen` needs a third entry point next to "JVM list" and "Open JAR/APK":

- **Android device** — runs `adb devices` to enumerate, picks one, then `adb shell pm list packages` to enumerate installed apps and pick one. The agent-android module is `adb push`-ed to the device, started under the chosen app's `Runtime.exec` (or launched as a Frida injection), and `adb forward tcp:port tcp:port` exposes its gRPC server back to the desktop.

This flow needs:
- `AdbService` (composeApp): wraps `adb` CLI invocations
- `AndroidProcess` data class: package name, PID, debuggable flag
- `AndroidAttachService`: equivalent of `AttachService` but uses ADB instead of JVM Attach API
- `AndroidAgentClassSource`: routes `listClasses` / `getBytecode` over the same gRPC contract

### Bytecode conversion at runtime

To present DEX classes to the same UI (Inspector, Strings, etc.), DEX bytes must be converted to JVM bytes on the fly. Two options:
1. **On device:** the agent-android does the DEX → JVM conversion before responding to `getClassBytecode` RPCs. Cleaner from the desktop's POV (it's just JVM bytes); heavier on device.
2. **On desktop:** agent ships raw DEX, desktop converts via dex-tools (already on `core`'s classpath after Step 2). Lighter on device; small per-class CPU cost on desktop.

Option 2 is preferable — desktop has more headroom and we already have the converter.

### Compatibility surface

If `agent-android` ever lands, the `protocol/.../bytesight.proto` may need optional fields per RPC (e.g. `BreakpointHit` adding ART-specific frame info). The proto's existing `reserved` ranges leave room for that without breaking the JVM agent. Keep the JVM agent's behavior on these new fields strictly "fall through to current semantics" so a single client UI works against both.

## Specific gaps to close (sub-tasks for the future plan)

1. **ADB integration:** spawning `adb devices`, parsing output, port-forward management, lifecycle of the forwarded port across re-attaches.
2. **Frida vs Xposed decision** with prototypes:
   - Frida: write a Frida-RPC bridge that exposes the same gRPC service contract.
   - Xposed: package agent-android as an Xposed module; use Xposed hooks for tracing.
3. **JDWP debugger backend:** map JDWP commands (set breakpoint, step, resume) to the existing `Breakpoint` / `Step` / `Resume` proto messages. JDWP's step kinds differ (DEPTH_OUT/INTO/OVER × SIZE_LINE/MIN); pick a reasonable default mapping.
4. **Heap snapshots via hprof:** the agent-android dumps hprof on demand and ships it to the desktop; desktop parses with an existing hprof lib (e.g. `com.squareup.haha`, deprecated but still functional, or `eclipse mat` Java API). Map hprof entries onto the existing `ObjectDetail` / `ClassHistogramEntry` protos.
5. **Method tracing on ART:** without ByteBuddy, options are (a) Frida `Interceptor.attach(...)` for native method hooks + Java VM Method API for method entry/exit, or (b) systrace-style atrace under `am profile`. Both produce events that can be reshaped into `MethodTraceEvent` proto.
6. **Capability set for `AndroidAgentClassSource`:** depends on which features actually work end-to-end after sub-tasks 2–5; declare a *subset* of the JVM agent's capabilities at first (e.g. STATIC_ANALYSIS + LIVE_TRACE only, no DEBUG) and broaden as features stabilize. The Sidebar's capability gating from Step 1 already handles this.

## Effort estimate (rough, when revisited)

- ADB integration + AttachScreen extension: ~3–5 days
- Frida/Xposed prototype + decision: ~1 week
- JDWP debugger backend + tests: ~2 weeks
- Heap snapshot import: ~3–5 days
- Method tracing: ~1 week
- Polish + integration: ~1 week

Plausibly **6–8 weeks** of focused work. That budget plus the unknowns around root-vs-non-root and ART version differences is why this was deferred.

## Decisions already made (don't re-debate)

- **Static APK analysis is enough for v1** (decided during brainstorming). Most users updating an analysis to a new app version actually want diff + static; full live ART is a different need.
- **No physical-device-only support** — emulator should work too. (Real-device USB is straightforward once ADB integration exists; this isn't a separate thing to design.)
- **Same protobuf service contract for both agents.** Don't fork the proto. Add fields with `reserved` slots if needed.

## Triggers to revisit this plan

Move this off the deferred list if any of these become true:
- Multiple users ask for live Android attach (we hear it more than once).
- The static APK + diff workflow is shipping but users still need runtime data we can't give them statically (e.g. dynamic SSL pinning bypass observation, runtime-generated strings, reflection-loaded class names).
- A Frida-driven third-party tool already gives us most of the way there and we just need the UI; in that case the cost might drop significantly.

Until then: leave this here, build Steps 1–4 cleanly so the Step-1 abstractions don't accidentally close the door on a future Android agent, and revisit when the demand is concrete.
