package com.intland.codebeamer.wiki.plugins.streamreuse;

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

/**
 * Matches source and target Test Case variants by commonItemId.
 * Supports all-item matching and strict selected-target matching.
 */
@Component
public class TestCaseMatcher {

    private final TrackerItemDao trackerItemDao;

    public TestCaseMatcher(TrackerItemDao trackerItemDao) {
        this.trackerItemDao = trackerItemDao;
    }

    public List<Pair> match(
            TrackerDto sourceTracker,
            TrackerDto targetTracker,
            Set<Integer> selectedTargetTestCaseIds,
            StreamReuseModels.Report report) {

        if (selectedTargetTestCaseIds != null &&
                !selectedTargetTestCaseIds.isEmpty()) {
            return matchSelectedTargets(
                    sourceTracker,
                    targetTracker,
                    selectedTargetTestCaseIds,
                    report);
        }

        return matchAll(
                sourceTracker,
                targetTracker,
                report);
    }

    private List<Pair> matchAll(
            TrackerDto sourceTracker,
            TrackerDto targetTracker,
            StreamReuseModels.Report report) {

        List<TrackerItemDto> sourceItems = activeItems(
                trackerItemDao.findByTracker(sourceTracker.getId()));
        List<TrackerItemDto> targetItems = activeItems(
                trackerItemDao.findByTracker(targetTracker.getId()));

        Map<Integer, List<TrackerItemDto>> targetsByCommonId =
                indexByCommonItemId(targetItems);
        List<Pair> matches = new ArrayList<>();

        for (TrackerItemDto sourceItem : sourceItems) {
            report.sourceTestCasesScanned++;
            List<TrackerItemDto> candidates = targetsByCommonId.getOrDefault(
                    Integer.valueOf(sourceItem.getCommonItemId()),
                    Collections.emptyList());

            if (candidates.size() == 1) {
                matches.add(new Pair(sourceItem, candidates.get(0)));
                report.matchedTestCases++;
            } else if (candidates.isEmpty()) {
                report.missingTargets++;
            } else {
                report.ambiguousTargets++;
            }
        }

        return matches;
    }

    private List<Pair> matchSelectedTargets(
            TrackerDto sourceTracker,
            TrackerDto targetTracker,
            Set<Integer> selectedTargetTestCaseIds,
            StreamReuseModels.Report report) {

        List<TrackerItemDto> sourceItems = activeItems(
                trackerItemDao.findByTracker(sourceTracker.getId()));
        Map<Integer, List<TrackerItemDto>> sourcesByCommonId =
                indexByCommonItemId(sourceItems);

        Map<Integer, TrackerItemDto> validatedTargets = new LinkedHashMap<>();
        List<String> validationErrors = new ArrayList<>();

        for (Integer requestedId : selectedTargetTestCaseIds) {
            TrackerItemDto target = trackerItemDao.findById(requestedId);

            if (target == null || target.isDeleted()) {
                validationErrors.add(
                        "Target Test Case " + requestedId +
                        " was not found, is inaccessible, or is deleted.");
                continue;
            }

            if (target.getTracker() == null ||
                    !targetTracker.getId().equals(
                            target.getTracker().getId())) {
                validationErrors.add(
                        "Target Test Case " + requestedId +
                        " does not belong to resolved target Test Case tracker " +
                        targetTracker.getId() + ".");
                continue;
            }

            validatedTargets.put(requestedId, target);
        }

        report.resolvedRequestedTargetTestCases = validatedTargets.size();
        report.invalidRequestedTargetTestCases = validationErrors.size();
        report.selectionValidationMessages.addAll(validationErrors);

        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected target Test Case validation failed: " +
                    String.join(" ", validationErrors));
        }

        List<Pair> matches = new ArrayList<>();

        for (TrackerItemDto target : validatedTargets.values()) {
            report.sourceTestCasesScanned++;
            List<TrackerItemDto> candidates = sourcesByCommonId.getOrDefault(
                    Integer.valueOf(target.getCommonItemId()),
                    Collections.emptyList());

            if (candidates.size() == 1) {
                matches.add(new Pair(candidates.get(0), target));
                report.matchedTestCases++;
            } else if (candidates.isEmpty()) {
                report.missingTargets++;
                report.selectionValidationMessages.add(
                        "No source Test Case was found in tracker " +
                        sourceTracker.getId() + " for target Test Case " +
                        target.getId() + " with commonItemId " +
                        target.getCommonItemId() + ".");
            } else {
                report.ambiguousTargets++;
                report.selectionValidationMessages.add(
                        "Multiple source Test Cases were found in tracker " +
                        sourceTracker.getId() + " for target Test Case " +
                        target.getId() + " with commonItemId " +
                        target.getCommonItemId() + ".");
            }
        }

        return matches;
    }

    private Map<Integer, List<TrackerItemDto>> indexByCommonItemId(
            List<TrackerItemDto> items) {
        Map<Integer, List<TrackerItemDto>> index = new HashMap<>();
        for (TrackerItemDto item : items) {
            index.computeIfAbsent(
                    Integer.valueOf(item.getCommonItemId()),
                    ignored -> new ArrayList<>()).add(item);
        }
        return index;
    }

    private List<TrackerItemDto> activeItems(List<TrackerItemDto> items) {
        List<TrackerItemDto> active = new ArrayList<>();
        if (items != null) {
            for (TrackerItemDto item : items) {
                if (item != null && !item.isDeleted()) {
                    active.add(item);
                }
            }
        }
        return active;
    }

    public static final class Pair {
        public final TrackerItemDto source;
        public final TrackerItemDto target;

        public Pair(TrackerItemDto source, TrackerItemDto target) {
            this.source = source;
            this.target = target;
        }
    }
}
