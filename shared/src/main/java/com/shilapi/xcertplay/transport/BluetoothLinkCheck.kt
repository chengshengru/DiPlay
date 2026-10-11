package com.shilapi.xcertplay.transport

/**
 * 区分真实 RFCOMM 链路与车机"假装"的链路(移植自 main #590,#354)。
 *
 * 连到一部手机要经历 paging、服务搜索与通道建立:最好也要几百毫秒。一些车机把通话和音乐
 * 放在厂商自己的蓝牙模块上,只给 Android 留了一个"立刻连接成功却从不到达一个字节"的
 * 适配器。在那里等 iAP2 对司机毫无意义,重试也无法改变结果。
 */
object BluetoothLinkCheck {
    /** 连接立刻返回且此后未收到任何字节时为真。 */
    fun looksUnreal(connectMillis: Long, bytesReceived: Long): Boolean =
        connectMillis < INSTANT_CONNECT_MILLIS && bytesReceived == 0L

    /**
     * 对照实验的结论。真实协议栈必须先在手机上找到服务才能连接,所以连接一个没有手机
     * 提供的服务不可能成功;若它也"成功"且同样立刻返回,说明适配器接受的连接哪儿也到不了。
     */
    fun controlProvesUnreal(controlConnected: Boolean, controlMillis: Long): Boolean =
        controlConnected && controlMillis < INSTANT_CONNECT_MILLIS

    /**
     * 比这更快的连接没有真正到达手机。链路不真实的车机回答用时 4–17ms;
     * 蓝牙可用的车机从不需要 100ms 以下。
     */
    const val INSTANT_CONNECT_MILLIS = 50L

    /**
     * 立刻建立的链路静默多久后进行验证。更早失败或结束且无字节的链路改为在失败当下验证。
     */
    const val SILENCE_MILLIS = 12_000L

    /** 失败文案中的标记;主机屏按它匹配。 */
    const val UNREAL_MARK = "connections that are not real"
}
