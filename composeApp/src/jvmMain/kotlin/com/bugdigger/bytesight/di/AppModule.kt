package com.bugdigger.bytesight.di

import com.bugdigger.ai.BytesightAgentServices
import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.debugger.ExecutionCursor
import com.bugdigger.bytesight.debugger.LiveCursor
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.AgentConfigStore
import com.bugdigger.bytesight.service.AttachService
import com.bugdigger.bytesight.service.BytesightAgentServicesImpl
import com.bugdigger.bytesight.service.CommentStore
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.ui.ai.AIViewModel
import com.bugdigger.bytesight.ui.attach.AttachViewModel
import com.bugdigger.bytesight.ui.browser.ClassBrowserViewModel
import com.bugdigger.bytesight.ui.debugger.DebuggerViewModel
import com.bugdigger.bytesight.ui.heap.HeapViewModel
import com.bugdigger.bytesight.ui.hierarchy.HierarchyViewModel
import com.bugdigger.bytesight.ui.inspector.InspectorViewModel
import com.bugdigger.bytesight.ui.settings.SettingsViewModel
import com.bugdigger.bytesight.ui.strings.StringsViewModel
import com.bugdigger.bytesight.ui.trace.TraceViewModel
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.core.decompiler.DecompilerOptions
import com.bugdigger.core.decompiler.VineflowerDecompiler
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
    single<ExecutionCursor> { LiveCursor(get(), get()) }

    // Decompiler configuration
    single { DecompilerOptions() }
    single<Decompiler> { VineflowerDecompiler(get()) }

    // AI agent services (wires BytesightAgentServices to real services)
    single<BytesightAgentServices> { BytesightAgentServicesImpl(get(), get(), get(), get()) }
    single { get<BytesightAgentServices>() as BytesightAgentServicesImpl }

    // ViewModels
    factoryOf(::AttachViewModel)
    factoryOf(::ClassBrowserViewModel)
    factoryOf(::HierarchyViewModel)
    factoryOf(::InspectorViewModel)
    factoryOf(::StringsViewModel)
    factoryOf(::TraceViewModel)
    factoryOf(::HeapViewModel)
    factoryOf(::DebuggerViewModel)
    factoryOf(::SettingsViewModel)
    factoryOf(::AIViewModel)
}
