package com.akylas.enforcedoze.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android service cannot be instantiated by the plain JVM suite. Pin its orchestration seam. */
class MusicEnterWiringTest {
    private fun service(): String = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()

    @Test fun coreEnterIsNotGatedByNeverFiringMusicSelection() {
        val source = service().substringAfter("private void enterDoze(boolean sensors)")
            .substringBefore("public void exitDoze")
        val core = source.indexOf("getController().enterCore(")
        val selection = source.indexOf("getPlayingPackageName(")
        assertTrue("core enter must run before starting asynchronous music selection", core >= 0 && core < selection)
    }

    @Test fun musicSelectionHasBoundedFallbackAndGenerationCheckedCompletion() {
        val source = service()
        assertTrue("never-connect needs a bounded fallback", source.contains("postDelayed(selectionTimeout, MUSIC_SELECTION_TIMEOUT_MS)"))
        assertTrue("late callbacks must share generation/admission-checked completion", source.contains("new DeferredFeatureSelection("))
    }
}
