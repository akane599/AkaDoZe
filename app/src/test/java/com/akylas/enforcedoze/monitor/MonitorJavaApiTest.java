package com.akylas.enforcedoze.monitor;

import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.parse.IdlingHistory;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MonitorJavaApiTest {
    @Test public void javaCanConstructConvertMergeSummarizeAndFormat() {
        JournalEvent off = JournalEvent.fromDozeEvent(new DozeEvent(EventType.SCREEN_OFF, "SCREEN_OFF"),
                1, 100, 1000, 10);
        JournalEvent on = new JournalEvent(1, 200, 1100, 10, Source.APP, EventType.SCREEN_ON);
        HistoryMergeResult merge = HistoryMerger.merge(Arrays.asList(off, on),
                new IdlingHistory(Collections.emptyList()), 100, 1);
        List<SessionSummary> summaries = SessionAggregator.summarize(merge.getEvents());
        assertEquals(100L, summaries.get(0).getDurationMs());
        assertEquals(100.0, summaries.get(0).getCoveragePercent().get(Coverage.UNKNOWN), 0.001);
        assertTrue(ReportFormatter.format(summaries, merge.getEvents(), "test", Collections.emptyMap())
                .contains("Sessions (1)"));
    }
}
