package com.swordfish.lemuroid.app.tv.shared

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.app.shared.GameContextMenuListener
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game

class GamePresenter(
    private val cardSize: Int,
    private val gameInteractor: GameInteractor,
) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any?,
    ) {
        if (item == null || viewHolder !is ViewHolder) return
        val game = item as Game
        viewHolder.mCardView.titleText = game.displayName()
        viewHolder.mCardView.contentText = GameUtils.getGameSubtitle(viewHolder.mCardView.context, game)
        viewHolder.mCardView.setMainImageDimensions(cardSize, cardSize)
        viewHolder.updateCardViewImage(game)
        viewHolder.showInstalled(isInstalled(viewHolder.view.context, game))
        viewHolder.view.setOnCreateContextMenuListener(GameContextMenuListener(gameInteractor, game))
    }

    /** A RomM game already downloaded to the TV or the USB drive. */
    private fun isInstalled(
        context: android.content.Context,
        game: Game,
    ): Boolean {
        if (!game.fileUri.startsWith("romm://")) return false
        val romId = com.swordfish.lemuroid.lib.romm.RommGameFiles.romId(android.net.Uri.parse(game.fileUri)) ?: return false
        return com.swordfish.lemuroid.lib.romm.RommGameFiles(context).downloadedIds().contains(romId)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        (cardView.findViewById<View>(androidx.leanback.R.id.content_text) as TextView).setTextColor(Color.LTGRAY)
        return ViewHolder(cardView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {
        val viewHolder = viewHolder as ViewHolder
        viewHolder.mCardView.mainImage = null
        viewHolder.view.setOnCreateContextMenuListener(null)
    }

    class ViewHolder(view: ImageCardView) : Presenter.ViewHolder(view) {
        val mCardView: ImageCardView = view

        // The card's usual colour behind the name, kept so a reused card can go back to it
        private val normalInfoBackground = view.infoAreaBackground

        /** Green behind the name and system when the game is downloaded and ready to play. */
        fun showInstalled(installed: Boolean) {
            if (installed) {
                mCardView.setInfoAreaBackgroundColor(INSTALLED_GREEN)
            } else {
                mCardView.infoAreaBackground = normalInfoBackground
            }
        }

        fun updateCardViewImage(game: Game) {
            CoverUtils.loadCover(game, mCardView.mainImageView)
        }
    }

    companion object {
        /** The design's green (the Play button colour), so installed games read as ready to play. */
        private val INSTALLED_GREEN = Color.rgb(16, 124, 16)
    }
}
