package com.metrolist.music.saraswati.craft.shuffle

import androidx.media3.common.MediaItem

enum class ChaturLabel { OFF, SHUFFLE, SMART }

data class ChaturButton(
    val mode: ShuffleMode,
    val label: ChaturLabel,
    val sparkle: Boolean,
    val enabled: Boolean,
    /** The mode a tap moves to, or null when the tap must do nothing. */
    val tapTo: ShuffleMode?,
)

object ChaturShuffleUi {
    fun buttonState(
        mode: ShuffleMode,
        isListenTogetherGuest: Boolean,
    ): ChaturButton =
        ChaturButton(
            mode = mode,
            label = ChaturLabel.valueOf(mode.name),
            sparkle = mode == ShuffleMode.SMART,
            enabled = !isListenTogetherGuest,
            tapTo = if (isListenTogetherGuest) null else mode.next(),
        )
}

fun MediaItem.isSmartPick(): Boolean = mediaMetadata.extras?.getBoolean(SmartShuffle.EXTRA_SMART_PICK) == true
