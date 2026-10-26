package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.persistence.dao.TrackerItemDao;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;

/**
 * Indexes executable Test Case Run items from a Test Run tracker and applies
 * the LATEST or ALL selection policy.
 *
 * Codebeamer creates a structural parent Test Run and one or more child Test
 * Case Run items. Only child Test Case Run items are eligible for cross-stream
 * reuse. A child execution item is identified by getParent() != null.
 */
@Component
public class TestRunSelector {

    private static final Logger LOG =
            LogManager.getLogger(TestRunSelector.class);

    private final TrackerItemDao trackerItemDao;

    public TestRunSelector(TrackerItemDao trackerItemDao) {
        this.trackerItemDao = trackerItemDao;
    }

    public Map<Integer, List<TrackerItemDto>> indexRuns(
            int trackerId) {

        Map<Integer, List<TrackerItemDto>> runsByTestCaseId =
                new HashMap<>();

        List<TrackerItemDto> trackerItems =
                trackerItemDao.findByTracker(trackerId);

        int deletedItems = 0;
        int structuralParentRuns = 0;
        int eligibleChildRuns = 0;
        int malformedReferenceRows = 0;

        if (trackerItems == null) {
            return runsByTestCaseId;
        }

        for (TrackerItemDto run : trackerItems) {
            if (run == null) {
                continue;
            }

            if (run.isDeleted()) {
                deletedItems++;
                continue;
            }

            /*
             * A structural parent Test Run has no parent item. The actual
             * executable Test Case Run is a child item and therefore has a
             * non-null parent. Exclude the structural parent from eligibility.
             */
            if (run.getParent() == null) {
                structuralParentRuns++;
                continue;
            }

            List<Map<Integer, String>> testCaseRows =
                    run.getTable(0);

            if (testCaseRows == null || testCaseRows.isEmpty()) {
                continue;
            }

            boolean indexed = false;

            for (int row = 0; row < testCaseRows.size(); row++) {
                try {
                    Integer testCaseId =
                            parseLinkedTestCaseId(
                                    run.getTableCell(0, row, 0));

                    runsByTestCaseId
                            .computeIfAbsent(
                                    testCaseId,
                                    ignored -> new ArrayList<>())
                            .add(run);

                    indexed = true;

                } catch (RuntimeException malformedReference) {
                    malformedReferenceRows++;

                    LOG.debug(
                            "STREAM_REUSE_SKIPPED_MALFORMED_TEST_CASE_REFERENCE " +
                                    "testRunId={}, row={}",
                            run.getId(),
                            row);
                }
            }

            if (indexed) {
                eligibleChildRuns++;
            }
        }

        for (List<TrackerItemDto> runs : runsByTestCaseId.values()) {
            runs.sort(
                    Comparator.comparing(
                            TrackerItemDto::getId));
        }

        LOG.info(
                "STREAM_REUSE_RUN_INDEX trackerId={}, totalItems={}, " +
                        "eligibleChildRuns={}, structuralParentRunsExcluded={}, " +
                        "deletedItemsExcluded={}, malformedReferenceRows={}",
                trackerId,
                trackerItems.size(),
                eligibleChildRuns,
                structuralParentRuns,
                deletedItems,
                malformedReferenceRows);

        return runsByTestCaseId;
    }

    public List<TrackerItemDto> select(
            List<TrackerItemDto> candidates,
            ReuseMode mode) {

        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<TrackerItemDto> uniqueRuns =
                new ArrayList<>(
                        new LinkedHashSet<>(candidates));

        uniqueRuns.sort(
                Comparator.comparing(
                        TrackerItemDto::getId));

        if (mode == ReuseMode.ALL) {
            return uniqueRuns;
        }

        return Collections.singletonList(
                uniqueRuns.get(uniqueRuns.size() - 1));
    }

    private Integer parseLinkedTestCaseId(String serializedCell) {
        if (serializedCell == null) {
            throw new IllegalArgumentException(
                    "The Test Case reference cell is null.");
        }

        int dashIndex = serializedCell.indexOf('-');
        int slashIndex = serializedCell.indexOf('/');

        if (dashIndex < 0 || slashIndex <= dashIndex) {
            throw new IllegalArgumentException(
                    "Unexpected Test Case reference cell: " +
                            serializedCell);
        }

        return Integer.valueOf(
                serializedCell.substring(
                        dashIndex + 1,
                        slashIndex));
    }
}
