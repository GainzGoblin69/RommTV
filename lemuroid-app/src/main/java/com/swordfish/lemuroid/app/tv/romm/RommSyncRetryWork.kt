package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.injection.AndroidWorkerInjection
import com.swordfish.lemuroid.lib.injection.WorkerKey
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommSession
import com.swordfish.lemuroid.lib.saves.StatesManager
import dagger.Binds
import dagger.android.AndroidInjector
import dagger.multibindings.IntoMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Uploads saves, save states and play time that couldn't reach RomM right after playing.
 * Runs whenever the TV is online, and keeps retrying (1 min, 2 min, 4 min… up to 5 hours apart) until it all gets through.
 */
class RommSyncRetryWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var coresSelection: CoresSelection

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            AndroidWorkerInjection.inject(this@RommSyncRetryWork)
            val session = RommSession(applicationContext)
            if (!session.isSignedIn) return@withContext Result.success()

            val sync = RommSync(applicationContext, statesManager)
            val client = RommClient(session)
            var remaining = 0
            for (uri in sync.pendingGames()) {
                val game = retrogradeDb.gameDao().selectByFileUri(uri)
                val romId = game?.let { RommSync.romId(it) }
                if (game == null || romId == null) {
                    sync.dropPending(uri)
                    continue
                }
                val ok =
                    runCatching {
                        val info = client.romDetail(romId)
                        val core = coresSelection.getCoreConfigForSystem(GameSystem.findById(game.systemId)).coreID
                        sync.retry(game, info, core)
                    }.onFailure { Timber.e(it, "Retry for ${game.title} failed") }.getOrDefault(false)
                if (!ok) remaining++
            }
            if (remaining > 0 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }

    companion object {
        private const val UNIQUE_WORK_ID = "RommSyncRetryWork"
        private const val MAX_ATTEMPTS = 30

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<RommSyncRetryWork>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_ID, ExistingWorkPolicy.KEEP, request)
        }

        /** At app start: picks up anything still waiting from an earlier session. */
        fun enqueueIfPending(context: Context) {
            if (RommSync.hasPending(context)) enqueue(context)
        }
    }

    @dagger.Module(subcomponents = [Subcomponent::class])
    abstract class Module {
        @Binds
        @IntoMap
        @WorkerKey(RommSyncRetryWork::class)
        abstract fun bindMyWorkerFactory(builder: Subcomponent.Builder): AndroidInjector.Factory<out ListenableWorker>
    }

    @dagger.Subcomponent
    interface Subcomponent : AndroidInjector<RommSyncRetryWork> {
        @dagger.Subcomponent.Builder
        abstract class Builder : AndroidInjector.Builder<RommSyncRetryWork>()
    }
}
