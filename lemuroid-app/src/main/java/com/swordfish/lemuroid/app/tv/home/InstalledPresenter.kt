package com.swordfish.lemuroid.app.tv.home

import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.util.Locale

/** A game downloaded to this TV, with how much space it takes. */
data class InstalledGame(val game: Game, val romId: Int, val sizeBytes: Long)

/** The TV's storage: free and total space, and how much the downloaded games use. */
data class StorageSummary(val freeBytes: Long, val totalBytes: Long, val gamesBytes: Long, val gamesCount: Int)

fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        bytes > 0 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
        else -> "0 MB"
    }

/** Cards for the Installed row: the storage summary first, then each downloaded game. */
class InstalledPresenter(private val cardSize: Int, private val cardPadding: Int) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(
        viewHolder: ViewHolder,
        item: Any,
    ) {
        val card = viewHolder.view as ImageCardView
        card.setMainImageDimensions(cardSize, cardSize)
        when (item) {
            is StorageSummary -> {
                card.titleText = "${formatBytes(item.freeBytes)} free"
                card.contentText = "Games: ${formatBytes(item.gamesBytes)} (${item.gamesCount}) of ${formatBytes(item.totalBytes)}"
                card.mainImageView.setImageResource(R.drawable.ic_storage_white_64dp)
                card.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
                card.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
            }
            is InstalledGame -> {
                card.titleText = item.game.displayName()
                card.contentText = formatBytes(item.sizeBytes)
                card.mainImageView.setPadding(0, 0, 0, 0)
                card.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
                CoverUtils.loadCover(item.game, card.mainImageView)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }
}
