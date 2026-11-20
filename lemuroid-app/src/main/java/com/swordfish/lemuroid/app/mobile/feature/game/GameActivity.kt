package com.swordfish.lemuroid.app.mobile.feature.game

import androidx.compose.runtime.Composable
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel

class GameActivity : BaseGameActivity() {
    @Composable
    override fun GameScreen(viewModel: BaseGameScreenViewModel) {
        MobileGameScreen(viewModel)
    }

    // The same game menu as on TVs (it adds the touch-screen extras on phones)
    override fun getDialogClass() = com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity::class.java
}
