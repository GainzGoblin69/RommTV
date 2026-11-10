package com.swordfish.lemuroid.app.tv.home

import com.swordfish.lemuroid.R

enum class TVSettingType(val icon: Int, val text: Int) {
    STOP_RESCAN(R.drawable.ic_stop_white_64dp, R.string.stop),
    RESCAN(R.drawable.ic_refresh_white_64dp, R.string.romm_sync),
    SHOW_ALL_FAVORITES(R.drawable.ic_more_games, R.string.show_all),
    ROMM_ACCOUNT(R.drawable.ic_cloud_sync_64dp, R.string.romm_account),
    SETTINGS(R.drawable.ic_settings_white_64dp, R.string.settings),
    SAVE_SYNC(R.drawable.ic_cloud_sync_64dp, R.string.save_sync),
}
