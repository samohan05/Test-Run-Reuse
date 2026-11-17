package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;

@Component
public class IdempotencyService {
    private static final Logger LOG = LogManager.getLogger(IdempotencyService.class);

    /** Builds the existing-reuse lookup once from the already-loaded Test Run tracker. */
    public Index buildIndex(List<TrackerItemDto> runs, int evidenceChoiceIndex) {
        Map<Integer, List<Set<Integer>>> byTargetTestCase = new HashMap<>();
        int indexedRuns = 0;
        if (runs != null) {
            for (TrackerItemDto run : runs) {
                if (run == null || run.isDeleted()) continue;
                Set<Integer> evidenceIds = evidenceIds(run.getChoiceList(evidenceChoiceIndex));
                if (evidenceIds.isEmpty()) continue;
                Set<Integer> linkedTargets = linkedTestCaseIds(run);
                for (Integer targetId : linkedTargets) {
                    byTargetTestCase.computeIfAbsent(targetId, ignored -> new java.util.ArrayList<>()).add(evidenceIds);
                    indexedRuns++;
                }
            }
        }
        LOG.info("STREAM_REUSE_IDEMPOTENCY_INDEX runsLoaded={}, indexedKeys={}, targetTestCases={}", runs == null ? 0 : runs.size(), indexedRuns, byTargetTestCase.size());
        return new Index(byTargetTestCase);
    }

    private Set<Integer> evidenceIds(List<?> values) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (values != null) for (Object value : values) if (value instanceof TrackerItemDto && ((TrackerItemDto) value).getId() != null) ids.add(((TrackerItemDto) value).getId());
        return Collections.unmodifiableSet(ids);
    }

    private Set<Integer> linkedTestCaseIds(TrackerItemDto run) {
        Set<Integer> ids = new LinkedHashSet<>();
        List<Map<Integer, String>> rows = run.getTable(0);
        if (rows == null) return ids;
        for (int row = 0; row < rows.size(); row++) {
            try {
                String cell = run.getTableCell(0, row, 0);
                int dash = cell == null ? -1 : cell.indexOf('-');
                int slash = dash < 0 ? -1 : cell.indexOf('/', dash + 1);
                if (dash >= 0 && slash > dash + 1) ids.add(Integer.valueOf(cell.substring(dash + 1, slash)));
            } catch (RuntimeException ignored) { }
        }
        return ids;
    }

    public static final class Index {
        private final Map<Integer, List<Set<Integer>>> byTargetTestCase;
        private Index(Map<Integer, List<Set<Integer>>> byTargetTestCase) { this.byTargetTestCase = byTargetTestCase; }
        public boolean exists(int targetTestCaseId, Collection<Integer> originIds) {
            List<Set<Integer>> existing = byTargetTestCase.get(targetTestCaseId);
            if (existing == null || existing.isEmpty()) return false;
            Set<Integer> wanted = new LinkedHashSet<>(originIds);
            for (Set<Integer> found : existing) if (found.equals(wanted) || found.containsAll(wanted)) return true;
            return false;
        }
    }
}
