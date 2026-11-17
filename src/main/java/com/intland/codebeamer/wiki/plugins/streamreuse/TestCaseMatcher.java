package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.intland.codebeamer.persistence.dao.TrackerItemDao;
import com.intland.codebeamer.persistence.dto.TrackerDto;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;

@Component
public class TestCaseMatcher {
    private final TrackerItemDao trackerItemDao;
    public TestCaseMatcher(TrackerItemDao trackerItemDao) { this.trackerItemDao = trackerItemDao; }

    /** Source and target trackers are each loaded exactly once. */
    public List<Pair> match(TrackerDto sourceTracker, TrackerDto targetTracker, Set<Integer> selectedIds, StreamReuseModels.Report report) {
        List<TrackerItemDto> sourceItems = activeItems(trackerItemDao.findByTracker(sourceTracker.getId()));
        List<TrackerItemDto> targetItems = activeItems(trackerItemDao.findByTracker(targetTracker.getId()));
        Map<Integer, List<TrackerItemDto>> sourcesByCommon = indexByCommonItemId(sourceItems);
        Map<Integer, List<TrackerItemDto>> targetsByCommon = indexByCommonItemId(targetItems);
        Map<Integer, TrackerItemDto> targetById = new LinkedHashMap<>();
        for (TrackerItemDto target : targetItems) targetById.put(target.getId(), target);
        if (selectedIds != null && !selectedIds.isEmpty()) return matchSelected(sourceTracker, selectedIds, targetById, sourcesByCommon, report);
        List<Pair> matches = new ArrayList<>();
        for (TrackerItemDto source : sourceItems) {
            report.sourceTestCasesScanned++;
            List<TrackerItemDto> candidates = targetsByCommon.getOrDefault(Integer.valueOf(source.getCommonItemId()), Collections.emptyList());
            if (candidates.size() == 1) { matches.add(new Pair(source, candidates.get(0))); report.matchedTestCases++; }
            else if (candidates.isEmpty()) report.missingTargets++; else report.ambiguousTargets++;
        }
        return matches;
    }

    private List<Pair> matchSelected(TrackerDto sourceTracker, Set<Integer> selectedIds, Map<Integer, TrackerItemDto> targets, Map<Integer, List<TrackerItemDto>> sourcesByCommon, StreamReuseModels.Report report) {
        List<String> errors = new ArrayList<>();
        List<Pair> matches = new ArrayList<>();
        for (Integer id : selectedIds) {
            TrackerItemDto target = targets.get(id);
            if (target == null) { errors.add("Target Test Case " + id + " was not found, is inaccessible, deleted, or outside the resolved target tracker."); continue; }
            if (isFolder(target)) { errors.add("Target Test Case " + id + " is a Folder information item."); continue; }
            report.sourceTestCasesScanned++;
            List<TrackerItemDto> candidates = sourcesByCommon.getOrDefault(Integer.valueOf(target.getCommonItemId()), Collections.emptyList());
            if (candidates.size() == 1) { matches.add(new Pair(candidates.get(0), target)); report.matchedTestCases++; }
            else if (candidates.isEmpty()) { report.missingTargets++; report.selectionValidationMessages.add("No source Test Case exists for target " + id + "."); }
            else { report.ambiguousTargets++; report.selectionValidationMessages.add("Multiple source Test Cases exist for target " + id + "."); }
        }
        report.resolvedRequestedTargetTestCases = selectedIds.size() - errors.size();
        report.invalidRequestedTargetTestCases = errors.size();
        report.selectionValidationMessages.addAll(errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Selected target Test Case validation failed: " + String.join(" ", errors));
        return matches;
    }

    private boolean isFolder(TrackerItemDto item) {
        try {
            Method m = item.getClass().getMethod("getCategories"); Object categories = m.invoke(item);
            if (categories instanceof Iterable<?>) for (Object category : (Iterable<?>) categories) {
                if (category == null) continue;
                Object id = category.getClass().getMethod("getId").invoke(category);
                Object name = category.getClass().getMethod("getName").invoke(category);
                if ((id instanceof Number && ((Number) id).intValue() == 1) || "folder".equalsIgnoreCase(String.valueOf(name))) return true;
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    private Map<Integer, List<TrackerItemDto>> indexByCommonItemId(List<TrackerItemDto> items) {
        Map<Integer, List<TrackerItemDto>> index = new HashMap<>();
        for (TrackerItemDto item : items) index.computeIfAbsent(Integer.valueOf(item.getCommonItemId()), ignored -> new ArrayList<>()).add(item);
        return index;
    }
    private List<TrackerItemDto> activeItems(List<TrackerItemDto> items) {
        List<TrackerItemDto> active = new ArrayList<>();
        if (items != null) for (TrackerItemDto item : items) if (item != null && !item.isDeleted()) active.add(item);
        return active;
    }
    public static final class Pair { public final TrackerItemDto source, target; public Pair(TrackerItemDto source, TrackerItemDto target) { this.source = source; this.target = target; } }
}
