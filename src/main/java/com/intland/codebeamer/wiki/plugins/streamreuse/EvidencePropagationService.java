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

@Component
public class EvidencePropagationService {
    private static final Logger LOG = LogManager.getLogger(EvidencePropagationService.class);
    public static final String FIELD_NAME = "Test Run Evidence";

    /** Resolves tracker layouts once per Preview/Execute request. */
    public EvidenceContext prepare(UserDto user, TrackerDto sourceTracker, TrackerDto targetTracker) {
        Integer sourceChoiceIndex = findChoiceIndex(user, sourceTracker, false);
        int targetChoiceIndex = findChoiceIndex(user, targetTracker, true).intValue();
        return new EvidenceContext(sourceChoiceIndex, targetChoiceIndex);
    }

    public EvidencePlan plan(UserDto user, TrackerItemDto sourceRun, TrackerDto targetTracker) {
        return prepare(user, sourceRun.getTracker(), targetTracker).plan(sourceRun);
    }

    public int index(UserDto user, TrackerDto tracker) {
        return findChoiceIndex(user, tracker, true).intValue();
    }

    private Integer findChoiceIndex(UserDto user, TrackerDto tracker, boolean required) {
        if (tracker == null) throw new IllegalArgumentException("The Test Run tracker is unavailable.");
        TrackerLayoutDto layout = TrackerManager.getInstance().findTrackerLayoutByTracker(user, tracker.getId());
        TrackerLayoutLabelDto field = layout == null ? null : layout.getFieldByName(FIELD_NAME);
        if (field == null) {
            if (required) throw new IllegalStateException("Field '" + FIELD_NAME + "' was not found on target Test Run tracker " + tracker.getId() + " (" + tracker.getName() + ").");
            return null;
        }
        int choiceIndex = TrackerLayoutLabelDto.getChoiceFieldIndex(field.getId());
        if (choiceIndex < 0) {
            if (required) throw new IllegalStateException("Field '" + FIELD_NAME + "' on target Test Run tracker " + tracker.getId() + " is not a user-defined Choice field.");
            return null;
        }
        return Integer.valueOf(choiceIndex);
    }

    private static void addUnique(List<TrackerItemDto> values, TrackerItemDto candidate) {
        if (candidate == null || candidate.getId() == null) return;
        for (TrackerItemDto existing : values) if (existing != null && Objects.equals(existing.getId(), candidate.getId())) return;
        values.add(candidate);
    }

    public static final class EvidenceContext {
        private final Integer sourceChoiceIndex;
        private final int targetChoiceIndex;
        private EvidenceContext(Integer sourceChoiceIndex, int targetChoiceIndex) {
            this.sourceChoiceIndex = sourceChoiceIndex;
            this.targetChoiceIndex = targetChoiceIndex;
        }
        public int targetChoiceIndex() { return targetChoiceIndex; }
        public EvidencePlan plan(TrackerItemDto sourceRun) {
            if (sourceRun == null || sourceRun.getId() == null) throw new IllegalArgumentException("The source Test Run is unavailable.");
            List<TrackerItemDto> effective = new ArrayList<>();
            String strategy = "EVIDENCE_FROM_IMMEDIATE_SOURCE";
            List<?> inherited = sourceChoiceIndex == null ? Collections.emptyList() : sourceRun.getChoiceList(sourceChoiceIndex.intValue());
            if (inherited != null && !inherited.isEmpty()) {
                for (Object value : inherited) if (value instanceof TrackerItemDto) addUnique(effective, (TrackerItemDto) value);
                if (!effective.isEmpty()) strategy = effective.size() > 1 ? "EVIDENCE_INHERITED_MULTIPLE" : "EVIDENCE_INHERITED_FROM_SOURCE";
            }
            if (effective.isEmpty()) {
                addUnique(effective, sourceRun);
                if (inherited != null && !inherited.isEmpty()) strategy = "EVIDENCE_FALLBACK_TO_IMMEDIATE_SOURCE";
            }
            return new EvidencePlan(targetChoiceIndex, effective, strategy);
        }
    }

    public static final class EvidencePlan {
        public final int targetChoiceIndex;
        public final List<TrackerItemDto> values;
        public final String strategy;
        EvidencePlan(int targetChoiceIndex, List<TrackerItemDto> values, String strategy) {
            this.targetChoiceIndex = targetChoiceIndex;
            this.values = Collections.unmodifiableList(new ArrayList<>(values));
            this.strategy = strategy;
        }
        public List<Integer> ids() {
            List<Integer> result = new ArrayList<>();
            for (TrackerItemDto value : values) if (value != null && value.getId() != null) result.add(value.getId());
            return result;
        }
    }
}
