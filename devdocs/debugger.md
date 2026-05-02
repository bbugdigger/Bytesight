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

---

## Phase 2 — implemented (hybrid ByteBuddy + slim JVMTI)

Phase 2 ships **line breakpoints anywhere in a method, Step Over/Into/Out, conditional breakpoints, hit count, decompiled-line breakpoints, and a real Pause RPC**. Implementation plan: see `~/.claude/plans/ok-so-this-is-fluttering-cloud.md`. Commit range on `debugger`: `d4d4daf..165b729`.

### Architectural pivot from the original Phase 2 plan

The plan called for a native JVMTI agent in C++ providing real `SetBreakpoint`/`SingleStep`/`GetLocalVariable*` semantics. **HotSpot 21 refuses 6 of the 10 capabilities we need at runtime attach** (`can_generate_breakpoint_events`, `can_generate_single_step_events`, `can_generate_frame_pop_events`, `can_generate_method_entry_events`, `can_generate_method_exit_events`, `can_access_local_variables` — see `agent/src/main/cpp/src/debugger/agent_init.cpp` for the working set). These are documented as *Phase: onload-only* in HotSpot's `jvmtiManageCapabilities.cpp` — they require iterating every loaded method at acquisition time, which HotSpot won't do post-VM-init.

JDWP doesn't help: `jdwp.dll` doesn't export `Agent_OnAttach` on JDK 13+, so it can't be loaded into a running JVM either.

The conclusion: **on HotSpot, full JVMTI debugging on an already-running JVM is impossible.** Bytesight therefore keeps ByteBuddy as the breakpoint mechanism (no special caps required) and uses a slim JVMTI native helper only for the things that *do* work in the live phase: `can_suspend` (real Pause via `SuspendThread`) and `can_get_line_numbers`. The native module lives at `agent/src/main/cpp/src/debugger/` as `bytesight_debugger.dll` next to `bytesight_heap.dll`.

### Loading the native helper

The DLL has to enter via `Agent_OnAttach` (live-phase capability acquisition), not `JNI_OnLoad`. composeApp's `AttachService.attachAgent`:

1. Extracts `bytesight_debugger.dll` from the agent JAR to a temp file.
2. Passes the path via the agent argument `debuggerDllPath=...` so the Java agent can `System.load()` the same file (binds JNI symbols).
3. After `vm.loadAgent(agentJar, ...)`, calls `vm.loadAgentPath(dllPath)` to trigger `Agent_OnAttach`.

One OS mapping, two JVM-level registrations. `NativeDebuggerBridge.isAvailable()` reports the combined state.

### Line breakpoints (`agent/.../debugger/LineProbeMethodVisitor`)

Every line of every method in any class with at least one bp gets an unconditional `INVOKESTATIC BreakpointInterceptor.onLineHit(class, method, sig, line)` probe at the bytecode offset of the line's first instruction. `onLineHit` consults `BreakpointManager.findLineBreakpoint(class, line)` at runtime — fast `Map.get` for inactive lines.

Probes are pre-injected on every line at first-bp install because **class retransformation cannot patch frames already executing** — the in-progress frame keeps running the pre-retransform bytecode. Pre-probing is what makes mid-execution bp adds (specifically the transient bps Step Over installs) take effect on the suspended frame when it resumes.

### Stepping (`agent/.../debugger/StepController`)

Without JVMTI single-step, stepping is "fan out transient breakpoints, first to fire wins":

| Step kind | Transient bps installed |
|-----------|-------------------------|
| Step Over | Line bp on every other line in current method + method-exit bp |
| Step Into | All Step Over transients + method-entry bp on every callee statically reachable via `INVOKE*` (extracted by `MethodAnalyzer` from the cached class bytes) |
| Step Out  | Just the method-exit bp on the current method |

When any transient fires, `StepController.tryCompleteStep` removes its siblings and emits a `StepCompleted` event instead of a normal `BreakpointHit`. Step Into is best-effort for virtual dispatch — it bps the static target class, not the actual subclass override. Step Out parks at the current method's exit (still inside the frame); a Resume click then returns to caller.

### Conditional bps + hit count (`agent/.../debugger/condition/ConditionEvaluator`)

A small expression language: literals, identifiers, `this.field`, arithmetic, comparisons, logical with short-circuit, parens. Identifier resolution: method args by name (or `arg0..argN` fallback when `-parameters` wasn't compiled in) → `this.field`. Parse + eval errors **fail open** — the bp still suspends with a warning so the user is never surprised by a silently-broken condition.

`BreakpointManager.recordAndEvaluate` is the gating hook called from every `BreakpointInterceptor` path. Increments `hit_count` unconditionally, then checks `skip_count`, then evaluates the condition. The `UpdateBreakpoint` RPC mutates these fields in place — no remove + reinstall.

**Line-bp limitation**: line probes don't have access to args/this/method, so conditions on line bps run with a null context. Lifting this requires the Phase 3 per-bp synthetic probe pattern.

### Decompiled-line breakpoints

`VineflowerDecompiler` now captures the previously-discarded `mapping: IntArray?` from `acceptClass` and `saveClassFile` and exposes it as `DecompiledLineMap` on `DecompilationResult.Success`. `DecompilerOptions.bytecodeSourceMapping` defaults to true. The Inspector decompiled-tab gutter resolves clicks via `originalLineFor` / `findNearbyOriginalLine` (round-down for unmapped clicks) and renders red bp dots on decompiled lines that map to currently-active bytecode bps via `decompiledLinesFor` (inverse lookup).

### What this Phase 2 PR does NOT do

Still on the roadmap, mostly because they're either OnLoad-only-cap dependent or genuinely deferred:

- Full LVT locals capture — needs `can_access_local_variables` (OnLoad-only) or per-bp synthetic probe.
- Logpoints, watches panel, evaluate-expression UI.
- Field watchpoints, exception breakpoints.
- Class reload (`redefineClasses`), break-on-classload.
- Time-Travel Debugging.

The `ExecutionCursor` abstraction is honored end-to-end (the UI never bypasses it), so a future `ReplayCursor` for TTD remains a drop-in.

---

## Phase 4 — Time-Travel Debugging (v1) — implemented

Phase 4 ships **observation-scrubbing TTD**: a toggleable in-memory ring buffer of the existing `DebuggerEvent` stream, plus a `ReplayCursor` that re-renders the same Call Stack / Variables / Source panels against any historical sequence id. Implementation plan: see `~/.claude/plans/so-i-heard-about-ticklish-mist.md`. **No agent-side changes** — Phase 4 is purely composeApp.

### What "TTD" means here (vs rr / Microsoft TTD)

We do **not** re-execute the JVM. We persist what the agent already emits (bp hits, step events, thread state changes) into a `RecordingLog` indexed by the existing monotonic `sequence_id`, then let the user scrub. Bit-exact deterministic replay would need syscall-level interception we cannot do from a Java agent on HotSpot 21 (Phase 2 already documented that 6 of 10 needed JVMTI caps are refused on live attach). Observation scrubbing is what's possible — and for RE workflows (one-shot triggers, anti-debug evasion via post-hoc analysis, sequence comprehension on obfuscated code) it's exactly the right shape.

### Architecture — `ReplayCursor` slots into the existing abstraction

The Phase 1 design doc specifically called this out (line 99): *"swapping to ReplayCursor for TTD later is invisible to the UI."* Phase 4 confirms it — the swap lives entirely in `DebuggerViewModel` via `combine(_cursorMode, liveCursor.X, replayCursor.X)`. Every existing panel (BreakpointsPanel, CallStackPanel, VariablesPanel, ThreadsPanel) reads from the same view-model StateFlows it always did; routing happens upstream.

```
LiveCursor (existing)  ──tee──►  RecordingLog (NEW, ring buffer, 10k events)
       │                              ▲
       │                              │ random-access by sequenceId
       │                              ▼
       │                        ReplayCursor (NEW, walks events ≤ playhead)
       │                              ▲
       ▼                              │
       └──────►  DebuggerViewModel ───┘   (combine + flatMapLatest by CursorMode)
                       │
                       ▼  unchanged StateFlows: threads, currentFrame, callStack, lastHit
                  Existing UI panels
```

### Recording control (`composeApp/.../debugger/RecordingLog.kt`)

Three states: `IDLE` (empty), `RECORDING` (actively appending the live stream), `REPLAY` (events present, capture stopped). `record()` is a no-op outside `RECORDING` so the tee in `LiveCursor.handleEvent` costs nothing when the user hasn't clicked Rec.

Ring-buffer eviction at 10k events by default. Eviction drops the oldest entries; the remaining log stays sorted by `sequence_id` (the agent assigns them monotonically), so binary search is O(log n).

### Storage format (`composeApp/.../debugger/RecordingFile.kt`)

`.btsrec` = length-prefixed stream of `DebuggerEvent` proto messages via `writeDelimitedTo` / `parseDelimitedFrom`. No header, no version, no magic. The proto's `oneof kind` and reserved field numbers (lines 151, 160, 170 of this doc) provide forward compatibility — additional event types loaded by an older client surface as `KindCase.KIND_NOT_SET`, which the UI tolerates.

### UI additions

- `RecordingBar.kt` — sits below `ControlBar`. Buttons: `[⏺ Rec]` / `[■ Stop Rec]`, `[⏮ Prev hit]`, `[⏭ Next hit]`, `[💾 Save]`, `[📂 Load]`, `[Clear]`, and (in replay) `[↩ Resume Live]`. Status badge shows `LIVE` / `REC ●` / `STOPPED` / `REPLAY @ seq=N`.
- `Timeline.kt` — bottom scrubber. Tick per event, color-coded by `kindCase` (red = hit, blue = step, secondary = thread state). Drag the playhead or click anywhere on the track to seek. Subsamples to ~500 visible ticks at high event counts to avoid Compose Canvas overdraw.
- File picker uses AWT `FileDialog` (JVM Compose Desktop has no native chooser).

### Reverse-step semantics

`prevHit` / `nextHit` walk to the previous/next `BreakpointHit` **on the currently focused thread** (cross-thread jumping is confusing in RE workflows). Both are no-ops when no hits exist; `nextHit` from Live mode jumps to the first recorded hit (intuitive "rewind to start"). `Step` events appear on the timeline scrubber but are skipped by `Prev/Next hit` — those buttons mean "interesting suspend point" = bp hits.

### What this Phase 4 PR does NOT do

Still on the roadmap (Phase 4 v2):

- Field-write probes (so "show me every write to field X" becomes a query).
- Heap snapshots at bp hit time (so `Inspect in Heap` works against historical state).
- True reverse stepping across decompiled lines (today: only across captured suspend points).
- Cross-session merge of recordings.

The TTD recording schema is forward-compatible with all of the above (proto reserved field numbers + `oneof kind`), so each is additive, not a rewrite.
