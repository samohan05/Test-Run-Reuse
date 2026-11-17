package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;

@Component
public class TestRunSelector {
    private static final Logger LOG = LogManager.getLogger(TestRunSelector.class);

    /** Indexes the caller's one-time Test Run tracker load. */
    public Map<Integer, List<TrackerItemDto>> indexRuns(List<TrackerItemDto> trackerItems) {
        Map<Integer, List<TrackerItemDto>> runsByTestCaseId = new HashMap<>();
        int deleted = 0, parents = 0, eligible = 0, malformed = 0;
        if (trackerItems == null) return runsByTestCaseId;
        for (TrackerItemDto run : trackerItems) {
            if (run == null) continue;
            if (run.isDeleted()) { deleted++; continue; }
            if (run.getParent() == null) { parents++; continue; }
            List<Map<Integer, String>> rows = run.getTable(0);
            if (rows == null || rows.isEmpty()) continue;
            boolean indexed = false;
            for (int row = 0; row < rows.size(); row++) {
                try {
                    Integer testCaseId = parseLinkedTestCaseId(run.getTableCell(0, row, 0));
                    runsByTestCaseId.computeIfAbsent(testCaseId, ignored -> new ArrayList<>()).add(run);
                    indexed = true;
                } catch (RuntimeException exception) { malformed++; }
            }
            if (indexed) eligible++;
        }
        for (List<TrackerItemDto> runs : runsByTestCaseId.values()) runs.sort(Comparator.comparing(TrackerItemDto::getId));
        LOG.info("STREAM_REUSE_RUN_INDEX totalItems={}, eligibleChildRuns={}, structuralParentRunsExcluded={}, deletedItemsExcluded={}, malformedReferenceRows={}", trackerItems.size(), eligible, parents, deleted, malformed);
        return runsByTestCaseId;
    }

    public List<TrackerItemDto> select(List<TrackerItemDto> candidates, ReuseMode mode) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        List<TrackerItemDto> unique = new ArrayList<>(new LinkedHashSet<>(candidates));
        unique.sort(Comparator.comparing(TrackerItemDto::getId));
        return mode == ReuseMode.ALL ? unique : Collections.singletonList(unique.get(unique.size() - 1));
    }

    private Integer parseLinkedTestCaseId(String cell) {
        if (cell == null) throw new IllegalArgumentException("Null Test Case reference");
        int dash = cell.indexOf('-'), slash = cell.indexOf('/', dash + 1);
        if (dash < 0 || slash <= dash + 1) throw new IllegalArgumentException("Malformed Test Case reference");
        return Integer.valueOf(cell.substring(dash + 1, slash));
    }
}
