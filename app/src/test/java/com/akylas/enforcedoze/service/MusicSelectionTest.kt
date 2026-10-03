package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MusicSelectionTest {
    @Test fun missingListenerJournalsUnavailableThenSelectsNotPlaying() {
        val events = mutableListOf<DozeEvent>()
        val selected = mutableListOf<Boolean?>()
        val selection = DeferredFeatureSelection(7, { 7 }, { true }) {
            assertEquals("MUSIC_SELECTION_UNAVAILABLE", events.single().detail)
            selected += it
        }
        selection.noListener(DozeEventSink { events += it })
        assertEquals(EventType.ERROR, events.single().type)
        assertEquals(listOf(false), selected)
        assertFalse("removed timeout or late callback cannot change selection", selection.complete(null))
        val source = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        val noListener = source.substringAfter("if (listener != null)")
            .substringAfter("return;").substringBefore("} catch (Exception error)")
        assertTrue("service null-listener path must use tested decision", noListener.contains("selection.noListener(runtime.getJournal())"))
    }

    @Test fun timeoutAndFailureKeepUnknownSelectionAndDoNotClaimMissingListener() {
        // The timeout and connected-listener failure paths both complete(null), preserving radios.
        for (path in listOf("timeout", "failure")) {
            val selected = mutableListOf<Boolean?>()
            val selection = DeferredFeatureSelection(7, { 7 }, { true }) { selected += it }
            assertTrue(path, selection.complete(null))
            assertEquals(path, listOf<Boolean?>(null), selected)
            assertFalse(path, selection.complete(false))
        }
        val source = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        assertTrue(source.contains("if (selection.complete(null)) runtime.getJournal().emit(new DozeEvent(EventType.ERROR, \"MUSIC_SELECTION_TIMEOUT\"))"))
        assertTrue(source.contains("if (selection.complete(null)) {\n                                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, \"MUSIC_SELECTION_FAILED\"))"))
    }

    @Test fun missingListenerCannotApplyCancelledOrStaleSelection() {
        val selected = mutableListOf<Boolean?>()
        val stale = DeferredFeatureSelection(7, { 8 }, { true }) { selected += it }
        stale.noListener(DozeEventSink {})
        val denied = DeferredFeatureSelection(7, { 7 }, { false }) { selected += it }
        denied.noListener(DozeEventSink {})
        val cancelled = DeferredFeatureSelection(7, { 7 }, { true }) { selected += it }
        cancelled.cancel()
        cancelled.noListener(DozeEventSink {})
        assertTrue(selected.isEmpty())
    }
}
