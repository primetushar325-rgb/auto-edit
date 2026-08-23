package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPlannerTest {

    private val planner = MotionPlanner()

    @Test
    fun `every clip receives a motion`() {
        val motions = planner.plan(50, FormulaCatalog.F01, 42L)
        assertEquals(50, motions.size)
        motions.forEach { assertNotNull(it.type) }
    }

    @Test
    fun `zero clips gives empty plan`() {
        assertTrue(planner.plan(0, FormulaCatalog.F01, 1L).isEmpty())
    }

    @Test
    fun `zoom in starts at 100 and ends between 104 and 108 for formula 01`() {
        val motions = planner.plan(60, FormulaCatalog.F01, 7L)
            .filter { it.type == MotionType.ZOOM_IN }
        assertTrue(motions.isNotEmpty())
        motions.forEach {
            assertEquals(1.0f, it.start.scale, 1e-4f)
            assertTrue("end ${it.end.scale}", it.end.scale in 1.04f..1.0801f)
        }
    }

    @Test
    fun `zoom out starts at max 108 and ends at 100`() {
        val motions = planner.plan(60, FormulaCatalog.F01, 9L)
            .filter { it.type == MotionType.ZOOM_OUT }
        assertTrue(motions.isNotEmpty())
        motions.forEach {
            assertTrue("start ${it.start.scale}", it.start.scale in 1.04f..1.0801f)
            assertEquals(1.0f, it.end.scale, 1e-4f)
        }
    }

    @Test
    fun `zoom stays inside safe limits for every motion type`() {
        val motions = planner.plan(200, FormulaCatalog.F01, 5L)
        motions.forEach { m ->
            assertTrue("start scale ${m.start.scale}", m.start.scale in 0.9f..1.1f)
            assertTrue("end scale ${m.end.scale}", m.end.scale in 0.9f..1.1f)
            // pan offsets must stay small (safe limits)
            assertTrue("x ${m.start.x}", m.start.x in -0.1f..0.1f)
            assertTrue("x ${m.end.x}", m.end.x in -0.1f..0.1f)
            assertTrue("y ${m.start.y}", m.start.y in -0.1f..0.1f)
            assertTrue("y ${m.end.y}", m.end.y in -0.1f..0.1f)
        }
    }

    @Test
    fun `no identical motion in consecutive clips`() {
        val motions = planner.plan(120, FormulaCatalog.F01, 11L)
        for (i in 1 until motions.size) {
            assertNotEquals(
                "consecutive ${motions[i].type} at index $i",
                motions[i - 1].type,
                motions[i].type
            )
        }
    }

    @Test
    fun `same seed produces the same sequence`() {
        val a = planner.plan(40, FormulaCatalog.F01, 99L)
        val b = planner.plan(40, FormulaCatalog.F01, 99L)
        assertEquals(a, b)
    }

    @Test
    fun `different seed produces a different sequence - randomize again`() {
        val a = planner.plan(40, FormulaCatalog.F01, 99L)
        val b = planner.plan(40, FormulaCatalog.F01, 100L)
        assertNotEquals(a, b)
    }

    @Test
    fun `formula 03 fixed mode always zoom in 100 to 110`() {
        val motions = planner.plan(10, FormulaCatalog.F03, 3L)
        motions.forEach { m ->
            assertEquals(MotionType.ZOOM_IN, m.type)
            assertEquals(1.0f, m.start.scale, 1e-4f)
            assertEquals(1.1f, m.end.scale, 1e-4f)
        }
    }

    @Test
    fun `formula 02 slow documentary only uses gentle motions`() {
        val motions = planner.plan(50, FormulaCatalog.F02, 4L)
        motions.forEach { m ->
            assertTrue(
                m.type in setOf(MotionType.ZOOM_IN, MotionType.ZOOM_OUT, MotionType.KEN_BURNS)
            )
            assertTrue(m.end.scale <= 1.05f)
        }
    }

    @Test
    fun `formula catalog has the required formula 01 with defaults`() {
        val f = FormulaCatalog.byId("F01")!!
        assertEquals("RANDOM CINEMATIC", f.tagline)
        assertEquals(3.0, f.clipDurationSec, 1e-9)
        assertEquals(MotionMode.RANDOM, f.motionMode)
        assertEquals(EasingType.EASE_IN_OUT, f.easing)
        assertTrue(FormulaCatalog.all.size >= 4)
        assertEquals(FormulaCatalog.F01, FormulaCatalog.default())
    }
}
