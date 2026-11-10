package com.swordfish.lemuroid.app.tv.home

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.util.Locale

/** A game downloaded to the TV or the USB drive, with how much space it takes. */
data class InstalledGame(val game: Game, val romId: Int, val sizeBytes: Long, val isUsb: Boolean)

/** One storage (the TV or the USB drive): free and total space, and how much the downloaded games use. */
data class StorageSummary(
    val isUsb: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    val gamesBytes: Long,
    val gamesCount: Int,
    val isDownloadTarget: Boolean,
)

/** A button card in the Installed row. */
data class InstalledAction(val type: Type, val title: String, val subtitle: String) {
    enum class Type { MOVE_ALL_TO_USB, SORT }
}

enum class InstalledSort(val label: String) {
    SIZE("Size"),
    LOCATION("Location"),
}

fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        bytes > 0 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
        else -> "0 MB"
    }

/** Cards for the Installed row: storage, actions, then each downloaded game (with a USB badge when it's on the drive). */
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
        card.badgeImage = null
        when (item) {
            is StorageSummary -> {
                val where = if (item.isUsb) "USB" else "TV"
                card.titleText = "$where: ${formatBytes(item.freeBytes)} free" + if (item.isDownloadTarget) "  ✓" else ""
                card.contentText = "${item.gamesCount} games, ${formatBytes(item.gamesBytes)} of ${formatBytes(item.totalBytes)}"
                icon(card, if (item.isUsb) R.drawable.ic_usb_white_64dp else R.drawable.ic_storage_white_64dp)
            }
            is InstalledAction -> {
                card.titleText = item.title
                card.contentText = item.subtitle
                icon(card, if (item.type == InstalledAction.Type.SORT) R.drawable.ic_sort_white_64dp else R.drawable.ic_usb_white_64dp)
            }
            is InstalledGame -> {
                card.titleText = item.game.displayName()
                card.contentText = formatBytes(item.sizeBytes) + if (item.isUsb) " · USB" else " · TV"
                if (item.isUsb) card.badgeImage = ContextCompat.getDrawable(card.context, R.drawable.ic_usb_white_24dp)
                card.mainImageView.setPadding(0, 0, 0, 0)
                card.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
                CoverUtils.loadCover(item.game, card.mainImageView)
            }
        }
    }

    private fun icon(
        card: ImageCardView,
        res: Int,
    ) {
        card.mainImageView.setImageResource(res)
        card.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
        card.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }
}
