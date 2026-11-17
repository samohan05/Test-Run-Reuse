package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import com.intland.codebeamer.persistence.dao.TrackerItemDao;
import com.intland.codebeamer.persistence.dto.TrackerItemDto;
import com.intland.codebeamer.persistence.dto.UserDto;

@Component
public class StreamReusePlanner {
    private static final Logger LOG = LogManager.getLogger(StreamReusePlanner.class);
    private final TestCaseMatcher matcher;
    private final TestRunSelector selector;
    private final EvidencePropagationService evidence;
    private final IdempotencyService idempotency;
    private final TrackerItemDao trackerItemDao;

    public StreamReusePlanner(TestCaseMatcher matcher, TestRunSelector selector, EvidencePropagationService evidence, IdempotencyService idempotency, TrackerItemDao trackerItemDao) {
        this.matcher = matcher; this.selector = selector; this.evidence = evidence; this.idempotency = idempotency; this.trackerItemDao = trackerItemDao;
    }

    public Plan plan(UserDto user, StreamReuseParameters parameters, StreamTrackerResolver.Resolution resolution, StreamReuseModels.Report report) {
        long started = System.currentTimeMillis();
        report.selectionScope = parameters.selectionScope();
        report.requestedTargetTestCaseIds.addAll(parameters.targetTestCaseIds);
        report.requestedTargetTestCaseCount = parameters.targetTestCaseIds.size();
        report.requestedTargetIdTokenCount = parameters.requestedTargetIdTokenCount;
        report.duplicateRequestedTargetIdCount = parameters.duplicateTargetIdCount;

        long stage = System.currentTimeMillis();
        List<TrackerItemDto> allTestRuns = trackerItemDao.findByTracker(resolution.sourceTestRunTracker.getId());
        LOG.info("STREAM_REUSE_PERF stage=LOAD_TEST_RUN_TRACKER elapsedMs={} rows={}", elapsed(stage), allTestRuns == null ? 0 : allTestRuns.size());

        stage = System.currentTimeMillis();
        Map<Integer, List<TrackerItemDto>> runsBySource = selector.indexRuns(allTestRuns);
        List<TrackerItemDto> targetTestRuns = resolution.sourceTestRunTracker.getId().equals(resolution.targetTestRunTracker.getId())
                ? allTestRuns : trackerItemDao.findByTracker(resolution.targetTestRunTracker.getId());
        EvidencePropagationService.EvidenceContext evidenceContext = evidence.prepare(user, resolution.sourceTestRunTracker, resolution.targetTestRunTracker);
        IdempotencyService.Index idempotencyIndex = idempotency.buildIndex(targetTestRuns, evidenceContext.targetChoiceIndex());
        LOG.info("STREAM_REUSE_PERF stage=BUILD_RUN_EVIDENCE_IDEMPOTENCY_INDEXES elapsedMs={}", elapsed(stage));

        stage = System.currentTimeMillis();
        List<TestCaseMatcher.Pair> pairs = matcher.match(resolution.sourceTestCaseTracker, resolution.targetTestCaseTracker, parameters.targetTestCaseIds, report);
        LOG.info("STREAM_REUSE_PERF stage=MATCH_TEST_CASES elapsedMs={} pairs={}", elapsed(stage), pairs.size());

        List<Work> work = new ArrayList<>();
        Map<Integer, EvidencePropagationService.EvidencePlan> evidenceCache = new HashMap<>();
        stage = System.currentTimeMillis();
        for (TestCaseMatcher.Pair pair : pairs) {
            List<TrackerItemDto> eligible = runsBySource.getOrDefault(pair.source.getId(), Collections.emptyList());
            report.eligibleRuns += eligible.size();
            List<TrackerItemDto> selected = selector.select(eligible, parameters.reuseMode);
            if (selected.isEmpty()) { report.testCasesWithoutRuns++; continue; }
            for (TrackerItemDto sourceRun : selected) {
                report.selectedRuns++;
                EvidencePropagationService.EvidencePlan evidencePlan = evidenceCache.computeIfAbsent(sourceRun.getId(), ignored -> evidenceContext.plan(sourceRun));
                StreamReuseModels.ItemResult item = new StreamReuseModels.ItemResult();
                item.sourceTestCaseId = pair.source.getId(); item.sourceTestCaseName = pair.source.getName();
                item.targetTestCaseId = pair.target.getId(); item.targetTestCaseName = pair.target.getName();
                item.sourceRunId = sourceRun.getId(); item.sourceRunName = sourceRun.getName();
                item.evidenceStrategy = evidencePlan.strategy; item.effectiveEvidenceIds.addAll(evidencePlan.ids());
                if (idempotencyIndex.exists(pair.target.getId(), evidencePlan.ids())) { item.action = "SKIP_ALREADY_REUSED"; report.alreadyReused++; }
                else { item.action = "CREATE"; report.plannedCopies++; work.add(new Work(pair.target, sourceRun, evidencePlan, item)); }
                report.results.add(item);
            }
        }
        LOG.info("STREAM_REUSE_PERF stage=BUILD_PLAN elapsedMs={} pairs={} selectedRuns={} plannedCopies={} alreadyReused={} evidenceCache={} totalElapsedMs={}", elapsed(stage), pairs.size(), report.selectedRuns, report.plannedCopies, report.alreadyReused, evidenceCache.size(), elapsed(started));
        return new Plan(work);
    }
    private long elapsed(long started) { return System.currentTimeMillis() - started; }
    public static final class Work {
        public final TrackerItemDto targetTestCase, sourceRun; public final EvidencePropagationService.EvidencePlan evidence; public final StreamReuseModels.ItemResult result;
        Work(TrackerItemDto target, TrackerItemDto run, EvidencePropagationService.EvidencePlan evidence, StreamReuseModels.ItemResult result) { this.targetTestCase=target;this.sourceRun=run;this.evidence=evidence;this.result=result; }
    }
    public static final class Plan { public final List<Work> work; Plan(List<Work> work) { this.work=work; } }
}
