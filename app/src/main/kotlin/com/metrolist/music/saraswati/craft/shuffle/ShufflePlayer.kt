package com.metrolist.music.saraswati.craft.shuffle

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder

interface ShufflePlayer {
    val currentIndex: Int
    val count: Int
    fun mediaIdAt(i: Int): String
    fun isPick(i: Int): Boolean
    fun append(items: List<MediaItem>)
    fun remove(indices: List<Int>)
    fun setOrder(order: IntArray)
    fun setShuffle(enabled: Boolean)
}

/** Reads [player] on every call, so a swapped-in player (audio offload) is picked up. */
class Media3ShufflePlayer(
    private val player: () -> ExoPlayer,
) : ShufflePlayer {
    override val currentIndex: Int get() = player().currentMediaItemIndex
    override val count: Int get() = player().mediaItemCount

    override fun mediaIdAt(i: Int): String = player().getMediaItemAt(i).mediaId

    override fun isPick(i: Int): Boolean =
        player().getMediaItemAt(i).mediaMetadata.extras?.getBoolean(SmartShuffle.EXTRA_SMART_PICK) == true

    override fun append(items: List<MediaItem>) {
        if (items.isNotEmpty()) player().addMediaItems(items)
    }

    override fun remove(indices: List<Int>) {
        val target = player()
        indices.sortedDescending().forEach { target.removeMediaItem(it) }
    }

    override fun setOrder(order: IntArray) {
        player().setShuffleOrder(DefaultShuffleOrder(order, System.currentTimeMillis()))
    }

    override fun setShuffle(enabled: Boolean) {
        player().shuffleModeEnabled = enabled
    }
}
