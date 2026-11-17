package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.time.Instant;
import org.springframework.stereotype.Component;
import com.intland.codebeamer.persistence.dao.UserDao;
import com.intland.codebeamer.persistence.dto.UserDto;

@Component
public class StreamReuseJob {
    private final UserDao users;
    private final StreamReuseCopyService copier;
    private final OperationRegistry registry;
    public StreamReuseJob(UserDao users, StreamReuseCopyService copier, OperationRegistry registry) { this.users=users;this.copier=copier;this.registry=registry; }
    public void execute(int userId, StreamReuseParameters parameters, StreamTrackerResolver.Resolution resolution,
            StreamReusePlanner.Plan plan, StreamReuseModels.Report report) {
        report.status=StreamReuseModels.Status.RUNNING.name();report.message="Execution is running.";report.remainingCopies=report.plannedCopies;report.lastUpdatedAt=Instant.now();
        UserDto user=users.findById(userId);
        if(user==null){report.status=StreamReuseModels.Status.FAILED.name();report.message="Requesting user no longer exists";report.lastError=report.message;report.lastUpdatedAt=Instant.now();return;}
        for(StreamReusePlanner.Work work:plan.work){
            report.currentSourceRunId=work.sourceRun.getId();report.currentSourceRunName=work.sourceRun.getName();
            report.currentTargetTestCaseId=work.targetTestCase.getId();report.currentTargetTestCaseName=work.targetTestCase.getName();report.lastUpdatedAt=Instant.now();
            try{
                Integer id=copier.copy(user,resolution.targetTestRunTracker,work,report.operationId);
                work.result.copiedRunId=id;work.result.action="CREATED";report.createdCopies++;
                if(work.evidence.strategy.startsWith("EVIDENCE_INHERITED"))report.evidenceInherited++;else if(work.evidence.strategy.contains("FALLBACK"))report.evidenceFallback++;else report.evidenceImmediate++;
            }catch(Exception ex){work.result.action="FAILED";work.result.reason=ex.getMessage();report.failedCopies++;report.lastError=ex.getMessage();}
            finally{report.processedCopies++;report.remainingCopies=Math.max(0,report.plannedCopies-report.processedCopies);report.lastUpdatedAt=Instant.now();}
        }
        report.currentSourceRunId=null;report.currentSourceRunName=null;report.currentTargetTestCaseId=null;report.currentTargetTestCaseName=null;
        report.completedAt=Instant.now();report.lastUpdatedAt=report.completedAt;report.remainingCopies=0;
        report.status=report.failedCopies==0?StreamReuseModels.Status.COMPLETED.name():StreamReuseModels.Status.COMPLETED_WITH_ERRORS.name();
        report.message="Bulk operation finished. Computed-field recalculation was not requested.";
    }
}
