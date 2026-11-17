package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * In-memory registry for bulk reuse operations.
 *
 * Wiki plug-ins can be rendered more than once while previewing, saving, or
 * displaying the same page. Repeating an operationKey must therefore return
 * the existing operation instead of throwing an exception.
 */
@Component
public class OperationRegistry {

    private final ConcurrentMap<String, StreamReuseModels.Report> byId =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, StreamReuseModels.Report> byKey =
            new ConcurrentHashMap<>();

    /**
     * Atomically reserves the operation ID and operation key.
     *
     * @return the existing report when the key was already registered, or the
     *         supplied report when this call registered a new operation.
     */
    public synchronized StreamReuseModels.Report registerOrGet(
            StreamReuseModels.Report candidate) {

        if (candidate == null) {
            throw new IllegalArgumentException(
                    "The operation report must not be null.");
        }

        String operationKey = normalize(candidate.operationKey);

        if (operationKey != null) {
            StreamReuseModels.Report existing = byKey.get(operationKey);
            if (existing != null) {
                return existing;
            }
        }

        byId.put(candidate.operationId, candidate);

        if (operationKey != null) {
            byKey.put(operationKey, candidate);
        }

        return candidate;
    }

    public StreamReuseModels.Report get(String operationId) {
        return operationId == null ? null : byId.get(operationId);
    }

    public StreamReuseModels.Report getByKey(String operationKey) {
        String normalized = normalize(operationKey);
        return normalized == null ? null : byKey.get(normalized);
    }

    
    public ProgressSnapshot progress(String operationId) {
        StreamReuseModels.Report report = get(operationId);
        return report == null ? null : ProgressSnapshot.from(report);
    }

    public static final class ProgressSnapshot {
        public String operationId, status, message, reuseMode, selectionScope;
        public String sourceStreamName, targetStreamName;
        public int plannedCopies, processedCopies, createdCopies, alreadyReused, failedCopies, remainingCopies;
        public Integer currentSourceRunId, currentTargetTestCaseId;
        public String currentSourceRunName, currentTargetTestCaseName, lastError;
        public String startedAt, lastUpdatedAt, completedAt;
        static ProgressSnapshot from(StreamReuseModels.Report r) {
            ProgressSnapshot p = new ProgressSnapshot();
            p.operationId=r.operationId;p.status=r.status;p.message=r.message;p.reuseMode=r.reuseMode;p.selectionScope=r.selectionScope;
            p.sourceStreamName=r.sourceStream==null?null:r.sourceStream.name;p.targetStreamName=r.targetStream==null?null:r.targetStream.name;
            p.plannedCopies=r.plannedCopies;p.processedCopies=r.processedCopies;p.createdCopies=r.createdCopies;p.alreadyReused=r.alreadyReused;p.failedCopies=r.failedCopies;
            p.remainingCopies=Math.max(0,r.plannedCopies-r.processedCopies);p.currentSourceRunId=r.currentSourceRunId;p.currentSourceRunName=r.currentSourceRunName;
            p.currentTargetTestCaseId=r.currentTargetTestCaseId;p.currentTargetTestCaseName=r.currentTargetTestCaseName;p.lastError=r.lastError;
            p.startedAt=r.startedAt==null?null:r.startedAt.toString();p.lastUpdatedAt=r.lastUpdatedAt==null?null:r.lastUpdatedAt.toString();p.completedAt=r.completedAt==null?null:r.completedAt.toString();
            return p;
        }
        public String getOperationId(){return operationId;} public String getStatus(){return status;} public String getMessage(){return message;}
        public String getReuseMode(){return reuseMode;} public String getSelectionScope(){return selectionScope;} public String getSourceStreamName(){return sourceStreamName;} public String getTargetStreamName(){return targetStreamName;}
        public int getPlannedCopies(){return plannedCopies;} public int getProcessedCopies(){return processedCopies;} public int getCreatedCopies(){return createdCopies;}
        public int getAlreadyReused(){return alreadyReused;} public int getFailedCopies(){return failedCopies;} public int getRemainingCopies(){return remainingCopies;}
        public Integer getCurrentSourceRunId(){return currentSourceRunId;} public String getCurrentSourceRunName(){return currentSourceRunName;}
        public Integer getCurrentTargetTestCaseId(){return currentTargetTestCaseId;} public String getCurrentTargetTestCaseName(){return currentTargetTestCaseName;}
        public String getLastError(){return lastError;} public String getStartedAt(){return startedAt;} public String getLastUpdatedAt(){return lastUpdatedAt;} public String getCompletedAt(){return completedAt;}
    }
private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
