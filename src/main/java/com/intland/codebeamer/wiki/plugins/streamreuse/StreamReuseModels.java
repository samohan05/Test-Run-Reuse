package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class StreamReuseModels {

    private StreamReuseModels() {
    }

    public enum Status {
        PREVIEW_READY,
        PREVIEW_BLOCKED,
        QUEUED,
        RUNNING,
        COMPLETED,
        COMPLETED_WITH_ERRORS,
        FAILED
    }

    public static final class EntityInfo {
        public final Integer id;
        public final String name;

        public EntityInfo(Integer id, String name) {
            this.id = id;
            this.name = name;
        }

        public Integer getId() { return id; }
        public String getName() { return name; }
    }

    public static final class ItemResult {
        public Integer sourceTestCaseId;
        public Integer targetTestCaseId;
        public Integer sourceRunId;
        public Integer copiedRunId;
        public String sourceTestCaseName;
        public String targetTestCaseName;
        public String sourceRunName;
        public String action;
        public String reason;
        public String evidenceStrategy;
        public List<Integer> effectiveEvidenceIds = new ArrayList<>();

        public Integer getSourceTestCaseId() { return sourceTestCaseId; }
        public Integer getTargetTestCaseId() { return targetTestCaseId; }
        public Integer getSourceRunId() { return sourceRunId; }
        public Integer getCopiedRunId() { return copiedRunId; }
        public String getSourceTestCaseName() { return sourceTestCaseName; }
        public String getTargetTestCaseName() { return targetTestCaseName; }
        public String getSourceRunName() { return sourceRunName; }
        public String getAction() { return action; }
        public String getReason() { return reason; }
        public String getEvidenceStrategy() { return evidenceStrategy; }
        public List<Integer> getEffectiveEvidenceIds() {
            return effectiveEvidenceIds;
        }
    }

    public static final class Report {
        public String operationId;
        public String operationKey;
        public String reuseMode;
        public String status;
        public String message;
        public String targetRunTrackerResolution;
        public boolean dryRun;
        public boolean diagnostic;
        public EntityInfo sourceStream;
        public EntityInfo targetStream;
        public EntityInfo sourceTestCaseTracker;
        public EntityInfo targetTestCaseTracker;
        public EntityInfo sourceTestRunTracker;
        public EntityInfo targetTestRunTracker;
        public Instant startedAt = Instant.now();
        public Instant completedAt;

        public String selectionScope = "ALL_MATCHED_TEST_CASES";
        public List<Integer> requestedTargetTestCaseIds = new ArrayList<>();
        public int requestedTargetTestCaseCount;
        public int requestedTargetIdTokenCount;
        public int duplicateRequestedTargetIdCount;
        public int resolvedRequestedTargetTestCases;
        public int invalidRequestedTargetTestCases;
        public List<String> selectionValidationMessages = new ArrayList<>();

        public int sourceTestCasesScanned;
        public int matchedTestCases;
        public int missingTargets;
        public int ambiguousTargets;
        public int testCasesWithoutRuns;
        public int eligibleRuns;
        public int selectedRuns;
        public int plannedCopies;
        public int createdCopies;
        public int alreadyReused;
        public int failedCopies;
        public int evidenceInherited;
        public int evidenceImmediate;
        public int evidenceFallback;
        public List<ItemResult> results = new ArrayList<>();

        public String getOperationId() { return operationId; }
        public String getOperationKey() { return operationKey; }
        public String getReuseMode() { return reuseMode; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
        public boolean isDryRun() { return dryRun; }
        public boolean isDiagnostic() { return diagnostic; }
        public EntityInfo getSourceStream() { return sourceStream; }
        public EntityInfo getTargetStream() { return targetStream; }
        public EntityInfo getSourceTestCaseTracker() {
            return sourceTestCaseTracker;
        }
        public EntityInfo getTargetTestCaseTracker() {
            return targetTestCaseTracker;
        }
        public EntityInfo getSourceTestRunTracker() {
            return sourceTestRunTracker;
        }
        public EntityInfo getTargetTestRunTracker() {
            return targetTestRunTracker;
        }
        public String getTargetRunTrackerResolution() {
            return targetRunTrackerResolution;
        }
        public Instant getStartedAt() { return startedAt; }
        public Instant getCompletedAt() { return completedAt; }

        public String getSelectionScope() { return selectionScope; }
        public List<Integer> getRequestedTargetTestCaseIds() {
            return requestedTargetTestCaseIds;
        }
        public int getRequestedTargetTestCaseCount() {
            return requestedTargetTestCaseCount;
        }
        public int getRequestedTargetIdTokenCount() {
            return requestedTargetIdTokenCount;
        }
        public int getDuplicateRequestedTargetIdCount() {
            return duplicateRequestedTargetIdCount;
        }
        public int getResolvedRequestedTargetTestCases() {
            return resolvedRequestedTargetTestCases;
        }
        public int getInvalidRequestedTargetTestCases() {
            return invalidRequestedTargetTestCases;
        }
        public List<String> getSelectionValidationMessages() {
            return selectionValidationMessages;
        }

        public int getSourceTestCasesScanned() {
            return sourceTestCasesScanned;
        }
        public int getMatchedTestCases() { return matchedTestCases; }
        public int getMissingTargets() { return missingTargets; }
        public int getAmbiguousTargets() { return ambiguousTargets; }
        public int getTestCasesWithoutRuns() {
            return testCasesWithoutRuns;
        }
        public int getEligibleRuns() { return eligibleRuns; }
        public int getSelectedRuns() { return selectedRuns; }
        public int getPlannedCopies() { return plannedCopies; }
        public int getCreatedCopies() { return createdCopies; }
        public int getAlreadyReused() { return alreadyReused; }
        public int getFailedCopies() { return failedCopies; }
        public int getEvidenceInherited() { return evidenceInherited; }
        public int getEvidenceImmediate() { return evidenceImmediate; }
        public int getEvidenceFallback() { return evidenceFallback; }
        public List<ItemResult> getResults() { return results; }
    }
}
