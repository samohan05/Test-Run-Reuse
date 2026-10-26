package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.persistence.dto.UserDto;

/**
 * Coordinates preview creation and explicit background execution.
 */
@Component
public class StreamReuseFacade {

    private final StreamTrackerResolver resolver;
    private final StreamReusePlanner planner;
    private final OperationRegistry registry;
    private final StreamReuseJob job;
    private final TaskExecutor executor;

    public StreamReuseFacade(
            StreamTrackerResolver resolver,
            StreamReusePlanner planner,
            OperationRegistry registry,
            StreamReuseJob job,
            @Qualifier("streamReuseExecutor") TaskExecutor executor) {

        this.resolver = resolver;
        this.planner = planner;
        this.registry = registry;
        this.job = job;
        this.executor = executor;
    }

    public StreamReuseModels.Report prepare(
            HttpServletRequest request,
            UserDto user,
            StreamReuseParameters parameters) {

        /*
         * A Wiki page can render the same plug-in several times. If an
         * execution key is already known, return its current report instead of
         * planning or submitting the operation again.
         */
        if (!parameters.dryRun) {
            StreamReuseModels.Report existing =
                    registry.getByKey(parameters.operationKey);

            if (existing != null) {
                return existing;
            }
        }

        StreamReuseModels.Report report =
                new StreamReuseModels.Report();

        report.operationId = UUID.randomUUID().toString();
        report.operationKey = parameters.operationKey;
        report.dryRun = parameters.dryRun;
        report.diagnostic = parameters.diagnostic;
        report.reuseMode = parameters.reuseMode.name();

        StreamTrackerResolver.Resolution resolution =
                resolver.resolve(request, parameters);

        report.sourceStream = resolution.sourceStream;
        report.targetStream = resolution.targetStream;

        report.sourceTestCaseTracker =
                new StreamReuseModels.EntityInfo(
                        resolution.sourceTestCaseTracker.getId(),
                        resolution.sourceTestCaseTracker.getName());

        report.targetTestCaseTracker =
                new StreamReuseModels.EntityInfo(
                        resolution.targetTestCaseTracker.getId(),
                        resolution.targetTestCaseTracker.getName());

        report.sourceTestRunTracker =
                new StreamReuseModels.EntityInfo(
                        resolution.sourceTestRunTracker.getId(),
                        resolution.sourceTestRunTracker.getName());

        report.targetTestRunTracker =
                new StreamReuseModels.EntityInfo(
                        resolution.targetTestRunTracker.getId(),
                        resolution.targetTestRunTracker.getName());

        report.targetRunTrackerResolution =
                resolution.targetRunStrategy;

        StreamReusePlanner.Plan plan =
                planner.plan(
                        user,
                        parameters,
                        resolution,
                        report);

        report.status =
                StreamReuseModels.Status.PREVIEW_READY.name();

        if (parameters.dryRun) {
            report.message =
                    "Dry run only. No data was created.";
            return report;
        }

        report.status = StreamReuseModels.Status.QUEUED.name();
        report.message = "Execution has been queued.";

        /*
         * Atomically reserve the operation key. A concurrent duplicate render
         * receives the existing report and must not submit another job.
         */
        StreamReuseModels.Report registered =
                registry.registerOrGet(report);

        if (registered != report) {
            return registered;
        }

        try {
            executor.execute(
                    () -> job.execute(
                            user.getId(),
                            parameters,
                            resolution,
                            plan,
                            report));

        } catch (RuntimeException submissionFailure) {
            report.status = StreamReuseModels.Status.FAILED.name();
            report.message =
                    "The background operation could not be submitted: " +
                            submissionFailure.getMessage();
            throw submissionFailure;
        }

        return report;
    }

    public StreamReuseModels.Report status(String operationId) {
        return registry.get(operationId);
    }
}
