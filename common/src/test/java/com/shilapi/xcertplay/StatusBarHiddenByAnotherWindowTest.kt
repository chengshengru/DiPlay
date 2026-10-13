package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 波次十一批次C（main #643/#674）：倒车摄像头隐藏状态栏时保持会话。 */
class StatusBarHiddenByAnotherWindowTest {
    private fun session(hideTopBar: Boolean = false, statusBarShown: Boolean? = true) = CarPlaySessionDisplay(
        width = 1280, height = 660, rotation = 0, hideTopBar = hideTopBar, hideBottomBar = false,
        windowWidth = 1280, windowHeight = 660, statusBarShown = statusBarShown,
    )

    @Test fun theReversingCameraHidingTheStatusBarKeepsTheSession() {
        assertTrue(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = false))
    }

    @Test fun growthWithTheStatusBarStillShownIsANewWindow() {
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = true))
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = null))
    }

    @Test fun aDriverWhoHidesTheStatusBarGetsTheLargerCanvas() {
        assertFalse(statusBarHiddenByAnotherWindow(session(hideTopBar = true), 1280, 720, statusBarShown = false))
    }

    @Test fun aSessionStartedWithoutAStatusBarIsNotAffected() {
        assertFalse(statusBarHiddenByAnotherWindow(session(statusBarShown = false), 1280, 720, statusBarShown = false))
        assertFalse(statusBarHiddenByAnotherWindow(session(statusBarShown = null), 1280, 720, statusBarShown = false))
    }

    @Test fun onlyAHeightGrowthAtTheSameWidthCounts() {
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1400, 720, statusBarShown = false))
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 660, statusBarShown = false))
    }
}
