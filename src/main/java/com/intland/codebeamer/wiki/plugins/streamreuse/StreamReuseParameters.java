package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validated Wiki parameters for Stream Test Run Reuse.
 *
 * targetTestCaseIds is optional. When absent or blank, all target Test Cases
 * that can be matched to the source tracker are considered. When supplied,
 * only the listed physical Test Case IDs from the resolved target tracker are
 * considered.
 */
public final class StreamReuseParameters {

    public final int sourceStreamId;
    public final int targetStreamId;
    public final int sourceTestCaseTrackerId;
    public final int sourceTestRunTrackerId;
    public final ReuseMode reuseMode;
    public final boolean dryRun;
    public final boolean diagnostic;
    public final String operationKey;
    public final Set<Integer> targetTestCaseIds;
    public final int requestedTargetIdTokenCount;
    public final int duplicateTargetIdCount;

    private StreamReuseParameters(
            int sourceStreamId,
            int targetStreamId,
            int sourceTestCaseTrackerId,
            int sourceTestRunTrackerId,
            ReuseMode reuseMode,
            boolean dryRun,
            boolean diagnostic,
            String operationKey,
            Set<Integer> targetTestCaseIds,
            int requestedTargetIdTokenCount,
            int duplicateTargetIdCount) {

        this.sourceStreamId = sourceStreamId;
        this.targetStreamId = targetStreamId;
        this.sourceTestCaseTrackerId = sourceTestCaseTrackerId;
        this.sourceTestRunTrackerId = sourceTestRunTrackerId;
        this.reuseMode = reuseMode;
        this.dryRun = dryRun;
        this.diagnostic = diagnostic;
        this.operationKey = operationKey;
        this.targetTestCaseIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(targetTestCaseIds));
        this.requestedTargetIdTokenCount = requestedTargetIdTokenCount;
        this.duplicateTargetIdCount = duplicateTargetIdCount;
    }

    public static StreamReuseParameters parse(Map<?, ?> parameters) {
        int sourceStreamId = positive(parameters, "sourceStreamId");
        int targetStreamId = positive(parameters, "targetStreamId");
        int sourceTestCaseTrackerId = positive(
                parameters, "sourceTestCaseTrackerId");
        int sourceTestRunTrackerId = positive(
                parameters, "sourceTestRunTrackerId");

        if (sourceStreamId == targetStreamId) {
            throw new IllegalArgumentException(
                    "sourceStreamId and targetStreamId must be different");
        }

        ReuseMode reuseMode = ReuseMode.parse(
                text(parameters, "reuseMode", "latest"));
        boolean dryRun = bool(parameters, "dryRun", true);
        boolean diagnostic = bool(parameters, "diagnostic", false);
        String operationKey = text(parameters, "operationKey", "").trim();

        if (!dryRun && operationKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "operationKey is required when dryRun=false");
        }
        if (operationKey.length() > 120 ||
                (!operationKey.isEmpty() &&
                        !operationKey.matches("[A-Za-z0-9._:-]+"))) {
            throw new IllegalArgumentException(
                    "operationKey must use A-Z, a-z, 0-9, dot, underscore, " +
                    "colon or hyphen and be <=120 characters");
        }

        ParsedIds selectedTargets = parseTargetTestCaseIds(
                text(parameters, "targetTestCaseIds", ""));

        return new StreamReuseParameters(
                sourceStreamId,
                targetStreamId,
                sourceTestCaseTrackerId,
                sourceTestRunTrackerId,
                reuseMode,
                dryRun,
                diagnostic,
                operationKey,
                selectedTargets.uniqueIds,
                selectedTargets.tokenCount,
                selectedTargets.duplicateCount);
    }

    public boolean hasSelectedTargetTestCases() {
        return !targetTestCaseIds.isEmpty();
    }

    public String selectionScope() {
        return hasSelectedTargetTestCases()
                ? "SELECTED_TARGET_TEST_CASES"
                : "ALL_MATCHED_TEST_CASES";
    }

    private static ParsedIds parseTargetTestCaseIds(String raw) {
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return new ParsedIds(unique, 0, 0);
        }

        String[] tokens = raw.split(",", -1);
        int tokenCount = 0;
        int duplicateCount = 0;

        for (String token : tokens) {
            String normalized = token == null ? "" : token.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "targetTestCaseIds contains an empty value. " +
                        "Provide positive integer IDs separated by commas.");
            }
            tokenCount++;
            final int id;
            try {
                id = Integer.parseInt(normalized);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Invalid targetTestCaseIds value '" + normalized +
                        "'. All values must be positive integer Test Case IDs.");
            }
            if (id <= 0) {
                throw new IllegalArgumentException(
                        "Invalid targetTestCaseIds value '" + normalized +
                        "'. All values must be positive integer Test Case IDs.");
            }
            if (!unique.add(Integer.valueOf(id))) {
                duplicateCount++;
            }
        }

        return new ParsedIds(unique, tokenCount, duplicateCount);
    }

    private static int positive(Map<?, ?> parameters, String name) {
        try {
            int value = Integer.parseInt(
                    text(parameters, name, "").trim());
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    name + " must be a positive integer");
        }
    }

    private static String text(
            Map<?, ?> parameters, String name, String defaultValue) {
        Object value = parameters.get(name);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static boolean bool(
            Map<?, ?> parameters, String name, boolean defaultValue) {
        String value = text(
                parameters, name, String.valueOf(defaultValue));
        if ("true".equalsIgnoreCase(value) ||
                "yes".equalsIgnoreCase(value) ||
                "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) ||
                "no".equalsIgnoreCase(value) ||
                "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                name + " must be true or false");
    }

    private static final class ParsedIds {
        private final LinkedHashSet<Integer> uniqueIds;
        private final int tokenCount;
        private final int duplicateCount;

        private ParsedIds(
                LinkedHashSet<Integer> uniqueIds,
                int tokenCount,
                int duplicateCount) {
            this.uniqueIds = uniqueIds;
            this.tokenCount = tokenCount;
            this.duplicateCount = duplicateCount;
        }
    }
}
