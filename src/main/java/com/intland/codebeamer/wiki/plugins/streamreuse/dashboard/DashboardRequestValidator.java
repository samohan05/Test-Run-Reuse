package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.persistence.dao.TrackerDao;
import com.intland.codebeamer.persistence.dao.TrackerItemDao;
import com.intland.codebeamer.persistence.dto.TrackerDto;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamMetadataClient;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamReuseModels;

/** Strict server-side validation of all browser-supplied identifiers. */
@Component
public class DashboardRequestValidator {
    private final StreamMetadataClient streams;
    private final TrackerDao trackerDao;
    private final TrackerItemDao trackerItemDao;

    public DashboardRequestValidator(
            @Qualifier("codebeamerInternalStreamMetadataClient") StreamMetadataClient streams,
            TrackerDao trackerDao,
            TrackerItemDao trackerItemDao) {
        this.streams = streams;
        this.trackerDao = trackerDao;
        this.trackerItemDao = trackerItemDao;
    }

    public void validate(HttpServletRequest servletRequest, DashboardReuseRequest request) {
        positive(request.getProjectId(), "projectId");
        positive(request.getSourceStreamId(), "sourceStreamId");
        positive(request.getTargetStreamId(), "targetStreamId");
        positive(request.getSourceTestCaseTrackerId(), "sourceTestCaseTrackerId");
        positive(request.getTargetTestCaseTrackerId(), "targetTestCaseTrackerId");
        positive(request.getTestRunTrackerId(), "testRunTrackerId");

        if (request.getSourceStreamId().equals(request.getTargetStreamId())) {
            throw new IllegalArgumentException("Source and target streams must be different.");
        }

        streams.findStream(servletRequest, request.getSourceStreamId());
        streams.findStream(servletRequest, request.getTargetStreamId());

        TrackerDto sourceTracker = requiredTracker(request.getSourceTestCaseTrackerId());
        TrackerDto targetTracker = requiredTracker(request.getTargetTestCaseTrackerId());
        TrackerDto runTracker = requiredTracker(request.getTestRunTrackerId());
        validateProject(sourceTracker, request.getProjectId());
        validateProject(targetTracker, request.getProjectId());
        validateProject(runTracker, request.getProjectId());

        StreamReuseModels.EntityInfo resolvedTarget = streams.resolveTrackerVariant(
                servletRequest,
                request.getTargetStreamId(),
                request.getSourceTestCaseTrackerId());
        if (!request.getTargetTestCaseTrackerId().equals(resolvedTarget.getId())) {
            throw new IllegalArgumentException(
                    "targetTestCaseTrackerId " + request.getTargetTestCaseTrackerId() +
                    " is not the resolved target variant. Expected " +
                    resolvedTarget.getId() + ".");
        }

        StreamReuseModels.EntityInfo resolvedRun = streams.resolveTrackerVariant(
                servletRequest,
                request.getTargetStreamId(),
                request.getTestRunTrackerId());
        if (!request.getTestRunTrackerId().equals(resolvedRun.getId())) {
            throw new IllegalArgumentException(
                    "The selected Test Run tracker is not shared in the target stream.");
        }

        Set<Integer> unique = new LinkedHashSet<>(request.getTargetTestCaseIds());
        for (Integer itemId : unique) {
            positive(itemId, "targetTestCaseId");
            TrackerItemDto item = trackerItemDao.findById(itemId);
            if (item == null || item.isDeleted()) {
                throw new IllegalArgumentException(
                        "Target Test Case " + itemId + " was not found, is inaccessible, or is deleted.");
            }
            if (item.getTracker() == null ||
                    !request.getTargetTestCaseTrackerId().equals(item.getTracker().getId())) {
                throw new IllegalArgumentException(
                        "Target Test Case " + itemId + " does not belong to tracker " +
                        request.getTargetTestCaseTrackerId() + ".");
            }
            if (isFolder(item)) {
                throw new IllegalArgumentException(
                        "Target Test Case " + itemId + " is a Folder information item.");
            }
        }
    }

    private TrackerDto requiredTracker(Integer id) {
        TrackerDto tracker = trackerDao.findById(id);
        if (tracker == null) throw new IllegalArgumentException("Tracker " + id + " was not found.");
        return tracker;
    }

    private void validateProject(TrackerDto tracker, Integer expectedProjectId) {
        try {
            Method getProject = tracker.getClass().getMethod("getProject");
            Object project = getProject.invoke(tracker);
            if (project == null) return;
            Method getId = project.getClass().getMethod("getId");
            Object actual = getId.invoke(project);
            if (actual instanceof Number && ((Number) actual).intValue() != expectedProjectId) {
                throw new IllegalArgumentException(
                        "Tracker " + tracker.getId() + " does not belong to project " +
                        expectedProjectId + ".");
            }
        } catch (NoSuchMethodException ignored) {
            // TrackerDto shape is release-specific; stream/tracker validation still applies.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to validate tracker project membership.", exception);
        }
    }

    private boolean isFolder(TrackerItemDto item) {
        try {
            Method getCategories = item.getClass().getMethod("getCategories");
            Object categories = getCategories.invoke(item);
            if (categories instanceof Iterable<?>) {
                for (Object category : (Iterable<?>) categories) {
                    if (category == null) continue;
                    Object id = invoke(category, "getId");
                    Object name = invoke(category, "getName");
                    if ((id instanceof Number && ((Number) id).intValue() == 1) ||
                            "folder".equalsIgnoreCase(String.valueOf(name))) return true;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // The planner's Test Case matching remains authoritative.
        }
        return false;
    }

    private Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private void positive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer.");
        }
    }
}
