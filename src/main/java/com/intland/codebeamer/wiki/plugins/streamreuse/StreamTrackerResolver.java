package com.intland.codebeamer.wiki.plugins.streamreuse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.persistence.dao.TrackerDao;
import com.intland.codebeamer.persistence.dto.TrackerDto;

@Component
public class StreamTrackerResolver {

    private final StreamMetadataClient streams;
    private final TrackerDao trackerDao;

    public StreamTrackerResolver(
            @Qualifier("codebeamerInternalStreamMetadataClient")
            StreamMetadataClient streams,
            TrackerDao trackerDao) {
        this.streams = streams;
        this.trackerDao = trackerDao;
    }

    public Resolution resolve(
            HttpServletRequest request,
            StreamReuseParameters parameters) {

        StreamReuseModels.EntityInfo sourceStream = streams.findStream(
                request, parameters.sourceStreamId);
        StreamReuseModels.EntityInfo targetStream = streams.findStream(
                request, parameters.targetStreamId);

        TrackerDto sourceTestCaseTracker = required(
                parameters.sourceTestCaseTrackerId,
                "source Test Case tracker");
        TrackerDto sourceTestRunTracker = required(
                parameters.sourceTestRunTrackerId,
                "source Test Run tracker");

        StreamReuseModels.EntityInfo targetTestCaseReference =
                streams.resolveTrackerVariant(
                        request,
                        parameters.targetStreamId,
                        parameters.sourceTestCaseTrackerId);
        TrackerDto targetTestCaseTracker = required(
                targetTestCaseReference.getId(),
                "target Test Case tracker");

        TrackerDto targetTestRunTracker;
        String targetRunStrategy;

        try {
            StreamReuseModels.EntityInfo targetRunReference =
                    streams.resolveTrackerVariant(
                            request,
                            parameters.targetStreamId,
                            parameters.sourceTestRunTrackerId);
            targetTestRunTracker = required(
                    targetRunReference.getId(),
                    "target Test Run tracker");
            targetRunStrategy = targetTestRunTracker.getId().equals(
                    sourceTestRunTracker.getId())
                    ? "SHARED_TRACKER"
                    : "STREAM_VARIANT";
        } catch (RuntimeException noTargetRunVariant) {
            /*
             * Existing V1 behavior: a Test Run tracker can be shared across
             * streams and therefore may not resolve as a separate variant.
             */
            targetTestRunTracker = sourceTestRunTracker;
            targetRunStrategy = "SHARED_TRACKER_FALLBACK";
        }

        return new Resolution(
                sourceStream,
                targetStream,
                sourceTestCaseTracker,
                targetTestCaseTracker,
                sourceTestRunTracker,
                targetTestRunTracker,
                targetRunStrategy);
    }

    private TrackerDto required(int id, String label) {
        TrackerDto tracker = trackerDao.findById(id);
        if (tracker == null) {
            throw new IllegalArgumentException(
                    label + " " + id + " was not found");
        }
        return tracker;
    }

    public static final class Resolution {
        public final StreamReuseModels.EntityInfo sourceStream;
        public final StreamReuseModels.EntityInfo targetStream;
        public final TrackerDto sourceTestCaseTracker;
        public final TrackerDto targetTestCaseTracker;
        public final TrackerDto sourceTestRunTracker;
        public final TrackerDto targetTestRunTracker;
        public final String targetRunStrategy;

        Resolution(
                StreamReuseModels.EntityInfo sourceStream,
                StreamReuseModels.EntityInfo targetStream,
                TrackerDto sourceTestCaseTracker,
                TrackerDto targetTestCaseTracker,
                TrackerDto sourceTestRunTracker,
                TrackerDto targetTestRunTracker,
                String targetRunStrategy) {
            this.sourceStream = sourceStream;
            this.targetStream = targetStream;
            this.sourceTestCaseTracker = sourceTestCaseTracker;
            this.targetTestCaseTracker = targetTestCaseTracker;
            this.sourceTestRunTracker = sourceTestRunTracker;
            this.targetTestRunTracker = targetTestRunTracker;
            this.targetRunStrategy = targetRunStrategy;
        }
    }
}
