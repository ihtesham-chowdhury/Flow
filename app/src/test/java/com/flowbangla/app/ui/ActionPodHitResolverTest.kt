package com.flowbangla.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionPodHitResolverTest {

    @Test
    fun testIdleStateReturnsMainOrConfirm() {
        val zone = ActionPodHitResolver.resolve(
            touchX = 50f, touchY = 50f,
            isRecording = false, isProcessing = false,
            cancelLeft = 10f, cancelRight = 50f, cancelTop = 10f, cancelBottom = 50f,
            confirmLeft = 150f, confirmRight = 190f, confirmTop = 10f, confirmBottom = 50f
        )
        assertEquals(PodHitZone.MAIN_OR_CONFIRM, zone)
    }

    @Test
    fun testRecordingStateLeftCircleReturnsCancel() {
        val zone = ActionPodHitResolver.resolve(
            touchX = 30f, touchY = 30f,
            isRecording = true, isProcessing = false,
            cancelLeft = 10f, cancelRight = 50f, cancelTop = 10f, cancelBottom = 50f,
            confirmLeft = 150f, confirmRight = 190f, confirmTop = 10f, confirmBottom = 50f,
            slop = 10f
        )
        assertEquals(PodHitZone.CANCEL, zone)
    }

    @Test
    fun testRecordingStateRightCircleReturnsConfirm() {
        val zone = ActionPodHitResolver.resolve(
            touchX = 170f, touchY = 30f,
            isRecording = true, isProcessing = false,
            cancelLeft = 10f, cancelRight = 50f, cancelTop = 10f, cancelBottom = 50f,
            confirmLeft = 150f, confirmRight = 190f, confirmTop = 10f, confirmBottom = 50f,
            slop = 10f
        )
        assertEquals(PodHitZone.MAIN_OR_CONFIRM, zone)
    }

    @Test
    fun testProcessingStateLeftCircleReturnsCancel() {
        val zone = ActionPodHitResolver.resolve(
            touchX = 25f, touchY = 25f,
            isRecording = false, isProcessing = true,
            cancelLeft = 10f, cancelRight = 50f, cancelTop = 10f, cancelBottom = 50f,
            confirmLeft = 0f, confirmRight = 0f, confirmTop = 0f, confirmBottom = 0f,
            slop = 10f
        )
        assertEquals(PodHitZone.CANCEL, zone)
    }

    @Test
    fun testProcessingStateOutsideLeftCircleReturnsNone() {
        val zone = ActionPodHitResolver.resolve(
            touchX = 160f, touchY = 30f,
            isRecording = false, isProcessing = true,
            cancelLeft = 10f, cancelRight = 50f, cancelTop = 10f, cancelBottom = 50f,
            confirmLeft = 0f, confirmRight = 0f, confirmTop = 0f, confirmBottom = 0f,
            slop = 10f
        )
        assertEquals(PodHitZone.NONE, zone)
    }
}
