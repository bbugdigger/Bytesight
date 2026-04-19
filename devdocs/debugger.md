# Debugger Tab — Design Doc

**v1 backend:** ByteBuddy-only (reuses existing `HookManager` + `TraceEventBuffer` patterns).
**v1 scope:** Phase 1 MVP only. Later phases are documented for context but are explicitly out of scope for the initial PR.

---

## Context

Bytesight is a JVM reverse-engineering workbench. Users attach the agent to a running JVM and analyze it via Class Browser, Inspector (bytecode/decompiled view), Hierarchy, Strings, Trace, Heap, and AI tabs. All analysis today is **passive** — read bytecode, read heap, log method entry/exit. There is no way to **pause** the target, **inspect a suspended frame**, or **drive execution step-by-step**.

A Debugger tab fills this gap. Unlike source-level debuggers, it must work on obfuscated/stripped classes (no .java, no debug symbols, method names like `a.b()`) — so bytecode-level debugging is the first-class mode.

The architecture is designed so **Time-Travel Debugging (TTD) can be added later without UI rewrites**: the UI reads from an `ExecutionCursor` abstraction that can be backed by either a live JVM or a recorded trace.

---

## What the Debugger tab shows (v1 MVP layout)

```
┌──────────────────────────────────────────────────────────────────┐
│ [Resume] [Pause] [Stop]  │  [Rec ●] (stub — wired in Phase 4)    │   ← control bar
├───────────────────┬─────────────────────────────────┬────────────┤
│ Breakpoints       │  Source / Bytecode (PC marker)  │ Threads    │
│ ──────────        │  ──────────────────────         │ ────────   │
│ ☑ Foo#bar:42      │  (reuse Inspector renderer;     │ ▶ main     │
│ ☑ Baz#<init>      │   highlight current instruction │ ■ worker-1 │
│                   │   with chevron + red bg)        │ ▶ worker-2 │
│ [+] Add           │                                 │            │
│                   │  [Bytecode] [Decompiled] tabs   │            │
├───────────────────┼─────────────────────────────────┴────────────┤
│ Call Stack        │  Variables                                   │
│ ──────────        │  ──────────                                  │
│ Foo.bar:42        │  ▼ this: Foo@0x1a2b                          │
│ Foo.run:12        │    name = "alice"                            │
│ Thread.run:834    │  ▼ args                                      │
│                   │    i (int) = 7                               │
└───────────────────┴──────────────────────────────────────────────┘
```

### v1 MVP panels — all shipping

1. **Control bar** — Resume (single thread or all), Pause (suspend all / one thread), Stop (clear all breakpoints + resume). Record toggle is rendered but disabled with tooltip "Time-travel recording — Phase 4."
2. **Breakpoints list**
   - **Line breakpoints** — set via Inspector gutter click (ASM LineNumberTable provides offset ↔ line mapping through existing `Instruction.lineNumber`).
   - **Method breakpoints** — entry / exit / both.
   - Enable / disable / delete per-breakpoint.
3. **Threads panel** — all threads with state (RUNNING / SUSPENDED / WAITING / BLOCKED), current frame summary, click-to-select.
4. **Call Stack panel** — selected thread's stack frames with `class#method:line`. Click a frame to re-render Variables for that frame.
5. **Variables panel** — `this` fields + method arguments for the selected frame. Object fields expand lazily. Right-click → "Inspect in Heap" (jumps to Heap tab) / "Ask AI" (jumps to AI tab). **Full locals are deferred to Phase 3** (requires synthetic per-breakpoint probe).
6. **Source / Bytecode view** — reuse Inspector's renderer; highlight current PC. Breakpoint gutter: click toggles. Two inner tabs: [Bytecode] / [Decompiled].

### Cross-tab integrations (v1)

- **Inspector → Debugger:** gutter click sets a line breakpoint (writes to shared `DebuggerState` singleton, mirrors how `ConnectionRegistry` is used).
- **Debugger → Inspector:** clicking a frame navigates Inspector to that class+method and highlights the PC.
- **Debugger → Heap:** right-click an object variable → "Inspect in Heap" (sets `pendingInspectorClass`-style field).
- **Debugger → AI:** "Ask AI about this frame" — bundles stack + locals into a prompt, navigates to AI tab (mirrors existing `pendingAIPrompt` flow from `AIScreen.kt`).

### Deferred panels (Phase 2+, documented so future work is obvious)

Phase 2: Step Over/Into/Out, conditional breakpoints, hit count, thread filter, logpoints, watches panel, evaluate-expression, field watchpoints, exception breakpoints.
Phase 3: full-locals synthetic probe, deadlock detector, branch coverage highlighting, break-on-classload, class reload (`redefineClasses`).
Phase 4: TTD — record mode, event log persistence, `ReplayCursor`, timeline scrubber, reverse-step.

---

## Architecture — designed for future TTD

The central abstraction is **`ExecutionCursor`** — a read-only view of "current program state." Everything UI-facing reads from it.

```
┌────────────────────────────────────────────────────────────┐
│  DebuggerScreen / DebuggerViewModel                        │
│            ▲                                               │
│            │ reads StateFlow<DebuggerUiState>              │
│            │ which derives from an ExecutionCursor         │
│            │                                               │
│  ┌─────────┴────────────────────────────────────────────┐  │
│  │  ExecutionCursor (interface)                         │  │
│  │    threads(): List<ThreadSnapshot>                   │  │
│  │    frame(thread, depth): FrameSnapshot               │  │
│  │    locals(frame): List<Variable>                     │  │
│  │    heap(objId): ObjectDetail                         │  │
│  └───────────────────▲─────────────▲────────────────────┘  │
│                      │             │                       │
│         ┌────────────┘             └───────────┐           │
│         │                                      │           │
│  ┌──────┴──────────┐                  ┌────────┴─────────┐ │
│  │ LiveCursor      │                  │ ReplayCursor     │ │
│  │ (v1)            │                  │ (v4 / TTD)       │ │
│  │ reads JVM state │                  │ reads recorded   │ │
│  │ via agent RPC   │                  │ event log at     │ │
│  └─────────────────┘                  │ sequence N       │ │
│                                       └──────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

Because the UI never calls the agent directly — it always asks the `ExecutionCursor` — swapping to `ReplayCursor` for TTD later is invisible to the UI. The Debugger tab gains a timeline scrubber at the bottom and reverse-step buttons, and the rest just works. **This is the single most important architectural constraint in v1.**

### v1 backend — ByteBuddy extension

Reuses patterns from `HookManager` (`agent/.../hook/HookManager.java`) and `TraceEventBuffer`:

1. **`SetBreakpoint` RPC** → `BreakpointManager.install(className, methodName, bytecodeOffset)`. Delegates to the same `Instrumentation.retransformClasses()` path `HookManager` already uses.
2. **`BreakpointInterceptor`** (new ByteBuddy advice class, mirrors `TraceInterceptor.java`):
   - `@Advice.OnMethodEnter` captures thread, args, `this` fields.
   - Emits `BreakpointHitEvent` onto `DebuggerEventBuffer`.
   - **Suspends the thread** via `LockSupport.park(breakpointToken)` until the UI issues Resume.
3. **`SubscribeDebuggerEvents` server-streaming RPC** delivers hits to the UI (same pattern as `SubscribeMethodTraces` in `BytesightAgentService.java`).
4. **`Resume` RPC** `LockSupport.unpark`s the thread.
5. **Frame inspection**: from a parked thread, call `Thread.getStackTrace()` for the call stack. Advice captures `this` + args into the event payload at suspend time.
6. **Line-offset breakpoints**: since ByteBuddy's `@Advice` intercepts whole methods only, v1 implements line breakpoints by emitting a `@Advice.OnMethodEnter` that checks `getCurrentLineNumber()` (computed from a precomputed line→offset table built via `core/.../BytecodeDisassembler.kt`) against the set of active line bps for that method. A single advice per method suffices.

**Known v1 limitation:** reading arbitrary LVT locals mid-method is not supported; v1 captures `this` fields + method arguments only. This covers ~80% of RE cases. Phase 3 adds a synthetic per-breakpoint probe (instrument a temporary advice class per bp that reads specific LVT slots via `@Advice.Local`) to close the gap.

### TTD groundwork we ship in v1

Four things that cost ~nothing in v1 but make v4 (TTD) a small diff:

1. **`ExecutionCursor` abstraction** — mandatory even in v1. Cost: one interface + one implementation.
2. **`DebuggerEventBuffer` with monotonic `sequenceId`** — every event already has a sequence number. Replay becomes a binary search later.
3. **Event schema with `sequence_id` + `timestamp_ns`** in the proto from day one (fields present even if unused by `LiveCursor`).
4. **`DebuggerEvent` as a discriminated union** (`oneof kind { ... }`) so adding replay-specific event types later is non-breaking.

**Non-goal:** bit-exact deterministic replay. "TTD" in this codebase means "scrub backward through captured observations," not "re-execute the JVM with identical side effects." Industry-grade deterministic TTD (rr, Microsoft TTD) requires syscall-level interception we cannot do purely in-process.

---

## Proto additions (v1)

```proto
service BytesightAgent {
  // ... existing ...

  rpc SetBreakpoint(SetBreakpointRequest) returns (BreakpointResponse);
  rpc RemoveBreakpoint(RemoveBreakpointRequest) returns (BreakpointResponse);
  rpc ListBreakpoints(ListBreakpointsRequest) returns (ListBreakpointsResponse);
  rpc SubscribeDebuggerEvents(SubscribeRequest) returns (stream DebuggerEvent);
  rpc Resume(ResumeRequest) returns (ResumeResponse);
  rpc Pause(PauseRequest) returns (PauseResponse);
}

message Breakpoint {
  string id = 1;
  oneof location {
    LineLocation line = 2;       // class + line
    MethodLocation method = 3;   // class + method + ENTRY|EXIT|BOTH
  }
  bool enabled = 10;
  // reserved 11-20 for Phase 2 (condition, hit_count, thread_filter, log_only, log_format)
}

message DebuggerEvent {
  int64 sequence_id = 1;   // monotonic — TTD keystone
  int64 timestamp_ns = 2;
  oneof kind {
    BreakpointHit hit = 10;
    ThreadStateChanged thread = 11;
    // reserved 12-19 for Phase 2+ (StepCompleted, ExceptionThrown, ...)
  }
}

message BreakpointHit {
  string breakpoint_id = 1;
  int64 thread_id = 2;
  string thread_name = 3;
  FrameSnapshot top_frame = 4;
  repeated FrameSnapshot stack = 5;
  // reserved 6-9 for Phase 2+ (locks_held, full_locals, ...)
}

message FrameSnapshot {
  string class_name = 1;
  string method_name = 2;
  string signature = 3;
  int32 bytecode_offset = 4;
  int32 line_number = 5;
  repeated Variable arguments = 6;
  repeated Variable this_fields = 7;
  // reserved 8 for Phase 3 (locals)
}

message Variable {
  string name = 1;
  string type_name = 2;
  string display_value = 3;   // human-readable
  int64 heap_tag = 4;         // 0 if primitive; otherwise resolvable via existing GetObject
}
```

Proto field numbers explicitly reserve space for Phase 2+ fields so future additions are non-breaking.

---

## Files to add / modify

### New (v1 MVP)

| Path | Purpose |
|------|---------|
| `composeApp/.../ui/debugger/DebuggerViewModel.kt` | MVVM ViewModel, exposes `StateFlow<DebuggerUiState>` |
| `composeApp/.../ui/debugger/DebuggerScreen.kt` | Screen composable with layout above |
| `composeApp/.../ui/debugger/components/ControlBar.kt` | Resume/Pause/Stop buttons |
| `composeApp/.../ui/debugger/components/BreakpointsPanel.kt` | Breakpoint list + add/remove/toggle |
| `composeApp/.../ui/debugger/components/ThreadsPanel.kt` | Thread list with state |
| `composeApp/.../ui/debugger/components/CallStackPanel.kt` | Frame list for selected thread |
| `composeApp/.../ui/debugger/components/VariablesPanel.kt` | Expandable `this` fields + args tree |
| `composeApp/.../ui/debugger/components/DebuggerSourceView.kt` | Wraps Inspector renderer with PC highlight + bp gutter |
| `composeApp/.../debugger/ExecutionCursor.kt` | Interface (cornerstone for TTD) |
| `composeApp/.../debugger/LiveCursor.kt` | v1 implementation backed by `AgentClient` |
| `composeApp/.../debugger/DebuggerState.kt` | Koin singleton — cross-tab bridge (mirrors `ConnectionRegistry`) |
| `agent/.../debugger/BreakpointManager.java` | Install / remove / list breakpoints via `Instrumentation` |
| `agent/.../debugger/BreakpointInterceptor.java` | ByteBuddy `@Advice` class; parks thread on hit |
| `agent/.../debugger/DebuggerEventBuffer.java` | Listener-pattern event buffer (copies `TraceEventBuffer`) |
| `agent/.../debugger/ThreadRegistry.java` | Tracks parked threads + their tokens for Resume |

### Modified (v1 MVP)

| Path | Change |
|------|--------|
| `protocol/src/main/proto/bytesight.proto` | Add the RPCs and messages above; rebuild |
| `composeApp/.../ui/navigation/Navigation.kt` | Add `DEBUGGER` to `Screen` enum + 🐞 icon |
| `composeApp/.../ui/navigation/Sidebar.kt` | Enable DEBUGGER when `isConnected` |
| `composeApp/.../App.kt` | Route DEBUGGER screen; wire `pendingDebuggerBreakpoint` cross-tab signal |
| `composeApp/.../data/AgentClient.kt` | Add `setBreakpoint()`, `removeBreakpoint()`, `listBreakpoints()`, `streamDebuggerEvents()`, `resume()`, `pause()` |
| `composeApp/.../di/AppModule.kt` | Register `DebuggerState` (single), `LiveCursor` (single bound to `ExecutionCursor`), `DebuggerViewModel` (factory) |
| `agent/.../server/BytesightAgentService.java` | Implement new RPCs (delegate to `BreakpointManager` + `DebuggerEventBuffer`) |
| `composeApp/.../ui/inspector/InspectorScreen.kt` | Add breakpoint gutter action (writes to `DebuggerState`) |
| `core/.../decompiler/BytecodeDisassembler.kt` *(if needed)* | Surface `Map<Int, Int>` of offset→line for a method (already computable from existing data; may just need an accessor) |
| `devdocs/plan.md` | Append one line: "Phase 5: Debugger — see debugger.md" |

---

## Verification plan

**End-to-end manual test** against the existing `sample` module:

1. `.\gradlew.bat build` — verify clean build.
2. `.\gradlew.bat :sample:run &` — start target.
3. `.\gradlew.bat :composeApp:run` — launch Bytesight. Attach to `sample`.
4. Navigate to Class Browser → find `com.bugdigger.sample.Sample` → open in Inspector.
5. Click the gutter on a line inside `main()`. Verify breakpoint appears in Debugger tab's Breakpoints panel.
6. Trigger the code path (input to `sample` via its own UI/stdin). Verify Debugger tab:
   - Thread appears SUSPENDED in Threads panel.
   - Call Stack panel shows the full stack.
   - Variables panel shows `args` and `this` fields.
   - Bytecode view highlights the PC with a chevron.
7. Click Resume → target continues; breakpoint re-arms.
8. Set a **method breakpoint** on `Sample#someMethod` (ENTRY). Verify it fires on invocation.
9. Disable the breakpoint → confirm it no longer fires.
10. Delete all breakpoints + Stop → verify agent retransforms classes back (HookManager already supports this).

**Integration test** at `composeApp/src/jvmTest/kotlin/.../integration/DebuggerIntegrationTest.kt`, gated by `@EnabledIf("isIntegrationTestEnabled")`:
- Programmatically attach to a spawned sample JVM.
- Install a line breakpoint via `AgentClient.setBreakpoint()`.
- Trigger method invocation via reflection.
- Assert `BreakpointHit` event received on stream within timeout.
- Assert `FrameSnapshot.arguments` matches expected values.
- Call `AgentClient.resume()`, assert sample JVM exits cleanly.

**Agent unit tests**:
- `BreakpointManagerTest` — install / remove / list on a classloader loaded with test classes; assert `@Advice` is applied.
- `DebuggerEventBufferTest` — listener registration, event ordering, sequence-id monotonicity.

**Build/test commands** (per `CLAUDE.md`):
```
.\gradlew.bat :agent:test
.\gradlew.bat :composeApp:jvmTest
.\gradlew.bat :composeApp:run
```

---

## Out of scope for this PR (tracked for future phases)

- Stepping (Over/Into/Out) — Phase 2
- Conditional breakpoints, logpoints, watches, evaluate-expression — Phase 2
- Field watchpoints, exception breakpoints — Phase 2
- Full locals capture — Phase 3
- Deadlock detector, branch coverage highlighting, break-on-classload, class reload — Phase 3
- Time-Travel Debugging (record, replay, timeline scrubber, reverse-step) — Phase 4

The `ExecutionCursor` indirection and the proto's reserved field numbers mean each of these is additive, not a rewrite.
