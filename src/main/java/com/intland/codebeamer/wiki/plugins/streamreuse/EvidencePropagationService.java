package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.manager.TrackerManager;
import com.intland.codebeamer.persistence.dto.TrackerDto;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;
import com.intland.codebeamer.persistence.dto.TrackerLayoutDto;
import com.intland.codebeamer.persistence.dto.TrackerLayoutLabelDto;
import com.intland.codebeamer.persistence.dto.UserDto;

/**
 * Resolves and propagates Test Run Evidence.
 *
 * Source-side evidence is optional. When the source Test Run tracker does not
 * contain the evidence field, or the source run has no evidence value, the
 * immediate source Test Run becomes the target evidence.
 *
 * Target-side evidence is mandatory because every copied Test Run must retain
 * execution provenance.
 */
@Component
public class EvidencePropagationService {

    private static final Logger LOG =
            LogManager.getLogger(EvidencePropagationService.class);

    public static final String FIELD_NAME = "Test Run Evidence";

    public EvidencePlan plan(
            UserDto user,
            TrackerItemDto sourceRun,
            TrackerDto targetTracker) {

        if (sourceRun == null || sourceRun.getTracker() == null) {
            throw new IllegalArgumentException(
                    "The source Test Run or its tracker is unavailable.");
        }

        if (targetTracker == null) {
            throw new IllegalArgumentException(
                    "The target Test Run tracker is unavailable.");
        }

        Integer sourceChoiceIndex =
                findChoiceIndex(
                        user,
                        sourceRun.getTracker(),
                        false);

        int targetChoiceIndex =
                findChoiceIndex(
                        user,
                        targetTracker,
                        true).intValue();

        List<TrackerItemDto> effectiveEvidence =
                new ArrayList<>();

        String strategy =
                "EVIDENCE_FROM_IMMEDIATE_SOURCE";

        List<?> inheritedEvidence =
                sourceChoiceIndex == null
                        ? Collections.emptyList()
                        : sourceRun.getChoiceList(
                        sourceChoiceIndex.intValue());

        if (inheritedEvidence != null &&
                !inheritedEvidence.isEmpty()) {

            for (Object value : inheritedEvidence) {
                if (value instanceof TrackerItemDto) {
                    addUnique(
                            effectiveEvidence,
                            (TrackerItemDto) value);
                }
            }

            if (!effectiveEvidence.isEmpty()) {
                strategy = effectiveEvidence.size() > 1
                        ? "EVIDENCE_INHERITED_MULTIPLE"
                        : "EVIDENCE_INHERITED_FROM_SOURCE";
            }
        }

        if (effectiveEvidence.isEmpty()) {
            addUnique(
                    effectiveEvidence,
                    sourceRun);

            if (inheritedEvidence != null &&
                    !inheritedEvidence.isEmpty()) {

                strategy =
                        "EVIDENCE_FALLBACK_TO_IMMEDIATE_SOURCE";
            }
        }

        LOG.debug(
                "STREAM_REUSE_EVIDENCE_PLAN sourceRunId={}, " +
                        "sourceTrackerId={}, targetTrackerId={}, " +
                        "sourceChoiceIndex={}, targetChoiceIndex={}, " +
                        "strategy={}, evidenceIds={}",
                sourceRun.getId(),
                sourceRun.getTracker().getId(),
                targetTracker.getId(),
                sourceChoiceIndex,
                targetChoiceIndex,
                strategy,
                ids(effectiveEvidence));

        return new EvidencePlan(
                targetChoiceIndex,
                effectiveEvidence,
                strategy);
    }

    /**
     * Required target-side lookup retained for IdempotencyService and copy
     * verification. This method throws a descriptive exception when the field
     * is missing or is not a user-defined Choice field.
     */
    public int index(
            UserDto user,
            TrackerDto tracker) {

        return findChoiceIndex(
                user,
                tracker,
                true).intValue();
    }

    private Integer findChoiceIndex(
            UserDto user,
            TrackerDto tracker,
            boolean required) {

        TrackerLayoutDto layout =
                TrackerManager.getInstance()
                        .findTrackerLayoutByTracker(
                                user,
                                tracker.getId());

        TrackerLayoutLabelDto field =
                layout == null
                        ? null
                        : layout.getFieldByName(FIELD_NAME);

        if (field == null) {
            if (required) {
                throw new IllegalStateException(
                        "Field '" + FIELD_NAME +
                                "' was not found on target Test Run tracker " +
                                tracker.getId() + " (" + tracker.getName() + ").");
            }

            LOG.debug(
                    "STREAM_REUSE_SOURCE_EVIDENCE_NOT_CONFIGURED " +
                            "sourceTrackerId={}, sourceTrackerName={}",
                    tracker.getId(),
                    tracker.getName());

            return null;
        }

        int choiceIndex =
                TrackerLayoutLabelDto.getChoiceFieldIndex(
                        field.getId());

        if (choiceIndex < 0) {
            if (required) {
                throw new IllegalStateException(
                        "Field '" + FIELD_NAME +
                                "' on target Test Run tracker " + tracker.getId() +
                                " is not a user-defined Choice field.");
            }

            LOG.warn(
                    "STREAM_REUSE_SOURCE_EVIDENCE_INVALID_FIELD_TYPE " +
                            "sourceTrackerId={}, fieldId={}",
                    tracker.getId(),
                    field.getId());

            return null;
        }

        return Integer.valueOf(choiceIndex);
    }

    private void addUnique(
            List<TrackerItemDto> values,
            TrackerItemDto candidate) {

        if (candidate == null || candidate.getId() == null) {
            return;
        }

        for (TrackerItemDto existing : values) {
            if (existing != null &&
                    Objects.equals(
                            existing.getId(),
                            candidate.getId())) {
                return;
            }
        }

        values.add(candidate);
    }

    private List<Integer> ids(
            List<TrackerItemDto> values) {

        List<Integer> ids = new ArrayList<>();

        for (TrackerItemDto value : values) {
            if (value != null && value.getId() != null) {
                ids.add(value.getId());
            }
        }

        return ids;
    }

    public static final class EvidencePlan {
        public final int targetChoiceIndex;
        public final List<TrackerItemDto> values;
        public final String strategy;

        EvidencePlan(
                int targetChoiceIndex,
                List<TrackerItemDto> values,
                String strategy) {

            this.targetChoiceIndex = targetChoiceIndex;
            this.values = Collections.unmodifiableList(
                    new ArrayList<>(values));
            this.strategy = strategy;
        }

        public List<Integer> ids() {
            List<Integer> result = new ArrayList<>();

            for (TrackerItemDto value : values) {
                if (value != null && value.getId() != null) {
                    result.add(value.getId());
                }
            }

            return result;
        }
    }
}
