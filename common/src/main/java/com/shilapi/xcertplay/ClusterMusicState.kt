// 波次十 #660：集群音乐卡片主线程状态快照（作者：TRAE AI）
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** 主线程单例快照；复用有界封面缓存，不持有/回收其 Bitmap。 */
internal object ClusterMusicState {
    data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Bitmap? = null,
        val changedAt: Long = 0L, val revision: Long = 0L)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    private var state = Snapshot()

    /** 发布最新曲目与封面；仅在曲目元数据变化时推进 revision 与时间戳。 */
    fun publish(info: CarPlayNowPlaying, artwork: Bitmap?) {
        main.post {
            val changed = trackChanged(state.info, info)
            state = Snapshot(info, artwork, if (changed) SystemClock.elapsedRealtime() else state.changedAt, state.revision + if (changed) 1 else 0)
            listeners.toList().forEach { it(state) }
        }
    }

    /** 必须在主线程注册；注册后立即回调当前快照，返回反注册 lambda。 */
    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    /** 曲目是否换了：标题/艺人/专辑/来源 App 变化，或双方都有时长且时长不同。 */
    fun trackChanged(old: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        old.title != next.title || old.artist != next.artist || old.album != next.album ||
            old.sourceApp != next.sourceApp ||
            (old.durationMillis != null && next.durationMillis != null && old.durationMillis != next.durationMillis)
}
