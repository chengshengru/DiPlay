package com.shilapi.xcertplay

/**
 * 首页卡片的传输方式判定（main #742，波次十三批次G，作者：TRAE AI）。
 * 首页会话使用 DiPlay 打开连接时保存的选择，且在会话打开前已保存。
 * The home card's session is the one DiPlay opened with the saved connection choice, saved before it opens.
 */
internal object HomeCardTransport {
    /** 会话运行中且未选择无线，即为 USB 有线会话。 */
    fun wired(sessionRunning: Boolean, wirelessSelected: Boolean): Boolean = sessionRunning && !wirelessSelected
}
