package com.intland.codebeamer.wiki.plugins.streamreuse;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.controller.rest.v2.exception.ResourceNotFoundException;
import com.intland.codebeamer.controller.rest.v2.model.reference.TrackerReferenceModel;
import com.intland.codebeamer.controller.rest.v2.stream.StreamRestController;
import com.intland.codebeamer.controller.rest.v2.stream.model.StreamModel;

/**
 * Version 1.2 internal metadata provider.
 *
 * Resolves Stream and tracker-variant metadata by invoking Codebeamer's
 * existing Spring-managed StreamRestController directly. This removes the
 * self-referential HTTP call, base URL discovery, connector-port dependency,
 * cookie forwarding, and reverse-proxy/hairpin-networking dependency.
 *
 * The controller is obtained from the active Codebeamer ApplicationContext.
 * It must never be instantiated with new because its Codebeamer dependencies
 * are injected by Spring.
 */
@Component("codebeamerInternalStreamMetadataClient")
@Primary
public class CodebeamerInternalStreamMetadataClient
        implements StreamMetadataClient {

    private static final Logger LOG = LogManager.getLogger(
            CodebeamerInternalStreamMetadataClient.class);

    private final ApplicationContext applicationContext;

    public CodebeamerInternalStreamMetadataClient(
            ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public StreamReuseModels.EntityInfo findStream(
            HttpServletRequest request,
            int streamId) {
        try {
            StreamModel stream = streamController().findStreamById(
                    Integer.valueOf(streamId), Boolean.FALSE);

            if (stream == null || stream.getId() == null) {
                throw new IllegalArgumentException(
                        "Stream " + streamId +
                                " was not found or is inaccessible.");
            }

            LOG.debug(
                    "STREAM_REUSE_INTERNAL_STREAM_RESOLVED " +
                            "streamId={}, streamName={}, sourceStreamId={}, " +
                            "initialStream={}",
                    stream.getId(),
                    stream.getName(),
                    stream.getSourceStreamId(),
                    stream.getInitialStream());

            return new StreamReuseModels.EntityInfo(
                    stream.getId(), stream.getName());

        } catch (ResourceNotFoundException exception) {
            throw new IllegalArgumentException(
                    "Stream " + streamId +
                            " was not found or is inaccessible.",
                    exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Internal Codebeamer Stream lookup failed for stream " +
                            streamId + ".",
                    exception);
        }
    }

    @Override
    public StreamReuseModels.EntityInfo resolveTrackerVariant(
            HttpServletRequest request,
            int streamId,
            int sourceTrackerId) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "The active HTTP request is required for permission-aware " +
                            "tracker variant resolution.");
        }

        try {
            TrackerReferenceModel tracker = streamController()
                    .getTrackerVariantInStream(
                            request,
                            Integer.valueOf(streamId),
                            Integer.valueOf(sourceTrackerId));

            if (tracker == null || tracker.getId() == null) {
                throw new IllegalArgumentException(
                        "No tracker variant was found for source tracker " +
                                sourceTrackerId + " in stream " + streamId + ".");
            }

            LOG.debug(
                    "STREAM_REUSE_INTERNAL_TRACKER_VARIANT_RESOLVED " +
                            "streamId={}, sourceTrackerId={}, targetTrackerId={}, " +
                            "targetTrackerName={}",
                    streamId,
                    sourceTrackerId,
                    tracker.getId(),
                    tracker.getName());

            return new StreamReuseModels.EntityInfo(
                    tracker.getId(), tracker.getName());

        } catch (ResourceNotFoundException exception) {
            throw new IllegalArgumentException(
                    "No tracker variant was found for source tracker " +
                            sourceTrackerId + " in stream " + streamId + ".",
                    exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Internal tracker variant resolution failed for source " +
                            "tracker " + sourceTrackerId + " in stream " +
                            streamId + ".",
                    exception);
        }
    }

    private StreamRestController streamController() {
        try {
            return applicationContext.getBean(StreamRestController.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "The Spring-managed Codebeamer StreamRestController bean " +
                            "is unavailable in the active application context.",
                    exception);
        }
    }
}
