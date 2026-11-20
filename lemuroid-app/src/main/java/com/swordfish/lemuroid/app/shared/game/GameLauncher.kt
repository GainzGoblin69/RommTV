package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class GameLauncher(
    private val coresSelection: CoresSelection,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
) {
    @OptIn(DelicateCoroutinesApi::class)
    fun launchGameAsync(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ): Boolean {
        if (GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
            // The last game may still be closing (saving and uploading its save): give it a moment, then
            // offer to close it rather than refusing
            GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                repeat(16) {
                    if (!GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
                        start(activity, game, loadSave, leanback)
                        return@launch
                    }
                    kotlinx.coroutines.delay(500)
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                android.app.AlertDialog.Builder(activity)
                    .setTitle("A game is still open")
                    .setMessage(
                        "Another game is still running in the background (or didn't close properly). " +
                            "Close it and start ${game.title}? Anything not saved in that game is lost.",
                    )
                    .setPositiveButton("Close it and play") { _, _ ->
                        closeGameProcess(activity)
                        GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            repeat(20) {
                                if (!GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
                                    start(activity, game, loadSave, leanback)
                                    return@launch
                                }
                                kotlinx.coroutines.delay(250)
                            }
                            activity.displayToast(R.string.game_process_another_game_running)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            return true
        }

        start(activity, game, loadSave, leanback)
        return true
    }

    /** Ends the separate process games run in (this app's own, so it's allowed). */
    private fun closeGameProcess(activity: Activity) {
        val manager = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.runningAppProcesses
            ?.filter { it.processName.endsWith(":game") }
            ?.forEach { android.os.Process.killProcess(it.pid) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun start(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ) {
        GlobalScope.launch {
            val system = GameSystem.findById(game.systemId)
            val coreConfig = coresSelection.getCoreConfigForSystem(system)
            gameLaunchTaskHandler.handleGameStart(activity.applicationContext)
            BaseGameActivity.launchGame(activity, coreConfig, game, loadSave, leanback)
        }
    }
}
