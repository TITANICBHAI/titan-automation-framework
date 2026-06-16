package com.titan.automation.di

import android.content.Context
import androidx.room.Room
import com.titan.automation.core.TitanCoroutineScopes
import com.titan.automation.core.TitanDispatchers
import com.titan.automation.data.db.MacroDatabase
import com.titan.automation.data.repository.SimpleMacroRepositoryImpl
import com.titan.automation.data.repository.WorkflowRepositoryImpl
import com.titan.automation.data.store.WorkflowDataStore
import com.titan.automation.debug.DebugSession
import com.titan.automation.debug.ExecutionTracer
import com.titan.automation.debug.FrameDebugger
import com.titan.automation.debug.GestureTimeline
import com.titan.automation.domain.repository.SimpleMacroRepository
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.workflow.WorkflowParser
import com.titan.automation.core.TitanLogger
import com.titan.automation.performance.BatteryMonitor
import com.titan.automation.performance.PerformanceMonitor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ── Database ──────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MacroDatabase =
        Room.databaseBuilder(context, MacroDatabase::class.java, "titan_macros.db")
            .fallbackToDestructiveMigration()
            .build()
}

// ── Repository ────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindWorkflowRepository(impl: WorkflowRepositoryImpl): WorkflowRepository

    @Binds
    @Singleton
    abstract fun bindSimpleMacroRepository(impl: SimpleMacroRepositoryImpl): SimpleMacroRepository
}

// ── Workflow infrastructure ───────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object WorkflowModule {

    @Provides
    @Singleton
    fun provideWorkflowParser(): WorkflowParser = WorkflowParser()

    @Provides
    @Singleton
    fun provideWorkflowDataStore(@ApplicationContext context: Context): WorkflowDataStore =
        WorkflowDataStore(context)
}

// ── Coroutine scopes & dispatchers ────────────────────────────────────────────
// TitanCoroutineScopes and TitanDispatchers use @Inject constructor — Hilt
// provides them automatically. Explicit @Provides are only needed for their
// individual qualifier-annotated properties when injected directly.

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    fun provideTitanCoroutineScopes(): TitanCoroutineScopes = TitanCoroutineScopes()

    @Provides
    @Singleton
    fun provideTitanDispatchers(): TitanDispatchers = TitanDispatchers()
}

// ── Logging ───────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun provideTitanLogger(): TitanLogger = TitanLogger()

}

// ── Debug & telemetry ─────────────────────────────────────────────────────────
// All debug classes use @Singleton @Inject constructor — auto-provided by Hilt.
// Explicit module entries are kept here for documentation and future overrides.

@Module
@InstallIn(SingletonComponent::class)
object DebugModule {

    @Provides
    @Singleton
    fun provideDebugSession(): DebugSession = DebugSession()

    @Provides
    @Singleton
    fun provideFrameDebugger(): FrameDebugger = FrameDebugger()

    @Provides
    @Singleton
    fun provideGestureTimeline(): GestureTimeline = GestureTimeline()

    @Provides
    @Singleton
    fun provideExecutionTracer(): ExecutionTracer = ExecutionTracer()
}

// ── Performance monitoring ────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object PerformanceModule {

    @Provides
    @Singleton
    fun providePerformanceMonitor(
        @ApplicationContext context: Context
    ): PerformanceMonitor = PerformanceMonitor(context)

    @Provides
    @Singleton
    fun provideBatteryMonitor(
        @ApplicationContext context: Context
    ): BatteryMonitor = BatteryMonitor(context)
}
