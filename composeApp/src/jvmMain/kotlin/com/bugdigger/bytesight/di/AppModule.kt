package com.bugdigger.bytesight.di

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.AttachService
import com.bugdigger.bytesight.ui.attach.AttachViewModel
import com.bugdigger.bytesight.ui.browser.ClassBrowserViewModel
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

    // Decompiler configuration
    single { DecompilerOptions() }
    single<Decompiler> { VineflowerDecompiler(get()) }

    // ViewModels
    factoryOf(::AttachViewModel)
    factoryOf(::ClassBrowserViewModel)
    factoryOf(::HierarchyViewModel)
    factoryOf(::InspectorViewModel)
    factoryOf(::StringsViewModel)
    factoryOf(::TraceViewModel)
    factoryOf(::SettingsViewModel)
}

