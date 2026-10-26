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

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
