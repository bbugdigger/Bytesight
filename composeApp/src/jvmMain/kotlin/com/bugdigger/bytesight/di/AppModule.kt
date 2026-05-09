package com.bugdigger.bytesight.di

import com.bugdigger.ai.BytesightAgentServices
import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.debugger.ExecutionCursor
import com.bugdigger.bytesight.debugger.LiveCursor
import com.bugdigger.bytesight.debugger.RecordingLog
import com.bugdigger.bytesight.debugger.ReplayCursor
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.AgentConfigStore
import com.bugdigger.bytesight.service.AttachService
import com.bugdigger.bytesight.service.BytesightAgentServicesImpl
import com.bugdigger.bytesight.service.CommentStore
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.ProjectService
import com.bugdigger.bytesight.service.ProjectSession
import com.bugdigger.bytesight.service.RenameAwareDecompiler
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.ui.ai.AIViewModel
import com.bugdigger.bytesight.ui.attach.AttachViewModel
import com.bugdigger.bytesight.ui.browser.ClassBrowserViewModel
import com.bugdigger.bytesight.ui.debugger.DebuggerViewModel
import com.bugdigger.bytesight.ui.diff.BytecodeDiffViewModel
import com.bugdigger.bytesight.ui.heap.HeapViewModel
import com.bugdigger.bytesight.ui.hierarchy.HierarchyViewModel
import com.bugdigger.bytesight.ui.inspector.InspectorViewModel
import com.bugdigger.bytesight.ui.settings.SettingsViewModel
import com.bugdigger.bytesight.ui.strings.StringsViewModel
import com.bugdigger.bytesight.ui.trace.TraceViewModel
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.core.decompiler.DecompilerOptions
import com.bugdigger.core.decompiler.VineflowerDecompiler
import com.bugdigger.core.diff.ProjectDiffer
import com.bugdigger.core.source.JarReader
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Main Koin module defining all application dependencies.
 */
val appModule = module {
    // Services
    singleOf(::AttachService)
    singleOf(::AgentClient)
    singleOf(::CommentStore)
    singleOf(::RenameStore)
    singleOf(::ConnectionRegistry)
    singleOf(::AgentConfigStore)

    // Debugger
    singleOf(::DebuggerState)
    single { RecordingLog() }
    single { LiveCursor(get(), get(), get()) }
    single { ReplayCursor(get(), initialSequenceId = 0L) }
    // ExecutionCursor binding kept for any consumers that still inject it directly;
    // the Debugger UI itself reads from DebuggerViewModel which routes between
    // live + replay cursors based on CursorMode.
    single<ExecutionCursor> { get<LiveCursor>() }

    // Decompiler configuration. RenameAwareDecompiler wraps Vineflower so
    // that the user's symbol renames are applied at the bytecode layer
    // (via ASM ClassRemapper) before decompilation runs — see
    // [RenameAwareDecompiler] for why this is the right layer.
    single { DecompilerOptions() }
    single<Decompiler> {
        RenameAwareDecompiler(
            delegate = VineflowerDecompiler(get()),
            renameStore = get(),
        )
    }

    // Static-source primitives. Used by JarClassSource today; future
    // ApkClassSource and BtsProjectClassSource will reuse these singletons.
    single { JarReader() }
    single { StaticHierarchyExtractor() }

    // Project file persistence (.bts save/load).
    single { Json { prettyPrint = true; ignoreUnknownKeys = true } }
    singleOf(::ProjectSession)
    single {
        ProjectService(
            connectionRegistry = get(),
            projectSession = get(),
            renameStore = get(),
            commentStore = get(),
            debuggerState = get(),
            hierarchyExtractor = get(),
            json = get(),
            bytesightVersion = "0.1.0",
        )
    }

    // AI agent services (wires BytesightAgentServices to real services)
    single<BytesightAgentServices> { BytesightAgentServicesImpl(get(), get(), get(), get()) }
    single { get<BytesightAgentServices>() as BytesightAgentServicesImpl }

    // ViewModels
    //
    // Most analytical tabs are singletons so their selection (class, method,
    // filters, etc.) survives tab switches. Each promoted VM resets its
    // user-facing state in setConnectionKey on a key change, so attaching to
    // a different JVM doesn't leak stale data from the previous attach.
    //
    // AttachViewModel + SettingsViewModel stay as factories: Attach has no
    // meaningful per-session state (you re-pick a process every time anyway),
    // and Settings is just a thin form over AgentConfigStore.
    factoryOf(::AttachViewModel)
    factoryOf(::SettingsViewModel)
    singleOf(::ClassBrowserViewModel)
    singleOf(::HierarchyViewModel)
    singleOf(::InspectorViewModel)
    singleOf(::StringsViewModel)
    singleOf(::TraceViewModel)
    singleOf(::HeapViewModel)
    singleOf(::DebuggerViewModel)
    singleOf(::AIViewModel)

    // BytecodeDiff (Step 4) — self-contained tab; loads its own files.
    single { ProjectDiffer() }
    singleOf(::BytecodeDiffViewModel)
}
