package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.intland.codebeamer.persistence.dto.TrackerItemDto;
import com.intland.codebeamer.persistence.dto.UserDto;

@Component
public class StreamReusePlanner {

    private final TestCaseMatcher matcher;
    private final TestRunSelector selector;
    private final EvidencePropagationService evidence;
    private final IdempotencyService idempotency;

    public StreamReusePlanner(
            TestCaseMatcher matcher,
            TestRunSelector selector,
            EvidencePropagationService evidence,
            IdempotencyService idempotency) {
        this.matcher = matcher;
        this.selector = selector;
        this.evidence = evidence;
        this.idempotency = idempotency;
    }

    public Plan plan(
            UserDto user,
            StreamReuseParameters parameters,
            StreamTrackerResolver.Resolution resolution,
            StreamReuseModels.Report report) {

        report.selectionScope = parameters.selectionScope();
        report.requestedTargetTestCaseIds.addAll(
                parameters.targetTestCaseIds);
        report.requestedTargetTestCaseCount =
                parameters.targetTestCaseIds.size();
        report.requestedTargetIdTokenCount =
                parameters.requestedTargetIdTokenCount;
        report.duplicateRequestedTargetIdCount =
                parameters.duplicateTargetIdCount;

        Map<Integer, List<TrackerItemDto>> runsBySourceTestCase =
                selector.indexRuns(
                        resolution.sourceTestRunTracker.getId());

        List<Work> work = new ArrayList<>();

        List<TestCaseMatcher.Pair> pairs = matcher.match(
                resolution.sourceTestCaseTracker,
                resolution.targetTestCaseTracker,
                parameters.targetTestCaseIds,
                report);

        for (TestCaseMatcher.Pair pair : pairs) {
            List<TrackerItemDto> eligible =
                    runsBySourceTestCase.getOrDefault(
                            pair.source.getId(),
                            Collections.emptyList());

            report.eligibleRuns += eligible.size();

            List<TrackerItemDto> selected =
                    selector.select(eligible, parameters.reuseMode);

            if (selected.isEmpty()) {
                report.testCasesWithoutRuns++;
                continue;
            }

            for (TrackerItemDto sourceRun : selected) {
                report.selectedRuns++;

                EvidencePropagationService.EvidencePlan evidencePlan =
                        evidence.plan(
                                user,
                                sourceRun,
                                resolution.targetTestRunTracker);

                StreamReuseModels.ItemResult item =
                        new StreamReuseModels.ItemResult();
                item.sourceTestCaseId = pair.source.getId();
                item.sourceTestCaseName = pair.source.getName();
                item.targetTestCaseId = pair.target.getId();
                item.targetTestCaseName = pair.target.getName();
                item.sourceRunId = sourceRun.getId();
                item.sourceRunName = sourceRun.getName();
                item.evidenceStrategy = evidencePlan.strategy;
                item.effectiveEvidenceIds.addAll(evidencePlan.ids());

                if (idempotency.exists(
                        user,
                        resolution.targetTestRunTracker.getId(),
                        pair.target.getId(),
                        evidencePlan.ids())) {
                    item.action = "SKIP_ALREADY_REUSED";
                    report.alreadyReused++;
                } else {
                    item.action = "CREATE";
                    report.plannedCopies++;
                    work.add(new Work(
                            pair.target,
                            sourceRun,
                            evidencePlan,
                            item));
                }

                report.results.add(item);
            }
        }

        return new Plan(work);
    }

    public static final class Work {
        public final TrackerItemDto targetTestCase;
        public final TrackerItemDto sourceRun;
        public final EvidencePropagationService.EvidencePlan evidence;
        public final StreamReuseModels.ItemResult result;

        Work(
                TrackerItemDto targetTestCase,
                TrackerItemDto sourceRun,
                EvidencePropagationService.EvidencePlan evidence,
                StreamReuseModels.ItemResult result) {
            this.targetTestCase = targetTestCase;
            this.sourceRun = sourceRun;
            this.evidence = evidence;
            this.result = result;
        }
    }

    public static final class Plan {
        public final List<Work> work;

        Plan(List<Work> work) {
            this.work = work;
        }
    }
}
