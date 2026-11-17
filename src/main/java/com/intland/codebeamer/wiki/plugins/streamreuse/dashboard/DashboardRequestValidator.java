package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import java.lang.reflect.Method;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.intland.codebeamer.persistence.dao.TrackerDao;
import com.intland.codebeamer.persistence.dto.TrackerDto;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamMetadataClient;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamReuseModels;

/** Fast structural validation. Item-set validation is batched in TestCaseMatcher from its one-time target tracker load. */
@Component
public class DashboardRequestValidator {
    private final StreamMetadataClient streams;
    private final TrackerDao trackerDao;
    public DashboardRequestValidator(@Qualifier("codebeamerInternalStreamMetadataClient") StreamMetadataClient streams, TrackerDao trackerDao) { this.streams=streams;this.trackerDao=trackerDao; }
    public void validate(HttpServletRequest servletRequest, DashboardReuseRequest request) {
        positive(request.getProjectId(),"projectId"); positive(request.getSourceStreamId(),"sourceStreamId"); positive(request.getTargetStreamId(),"targetStreamId");
        positive(request.getSourceTestCaseTrackerId(),"sourceTestCaseTrackerId"); positive(request.getTargetTestCaseTrackerId(),"targetTestCaseTrackerId"); positive(request.getTestRunTrackerId(),"testRunTrackerId");
        if (request.getSourceStreamId().equals(request.getTargetStreamId())) throw new IllegalArgumentException("Source and target streams must be different.");
        streams.findStream(servletRequest,request.getSourceStreamId()); streams.findStream(servletRequest,request.getTargetStreamId());
        TrackerDto source=requiredTracker(request.getSourceTestCaseTrackerId()),target=requiredTracker(request.getTargetTestCaseTrackerId()),run=requiredTracker(request.getTestRunTrackerId());
        validateProject(source,request.getProjectId());validateProject(target,request.getProjectId());validateProject(run,request.getProjectId());
        StreamReuseModels.EntityInfo resolvedTarget=streams.resolveTrackerVariant(servletRequest,request.getTargetStreamId(),request.getSourceTestCaseTrackerId());
        if(!request.getTargetTestCaseTrackerId().equals(resolvedTarget.getId()))throw new IllegalArgumentException("targetTestCaseTrackerId is not the resolved target variant. Expected "+resolvedTarget.getId()+".");
        StreamReuseModels.EntityInfo resolvedRun=streams.resolveTrackerVariant(servletRequest,request.getTargetStreamId(),request.getTestRunTrackerId());
        if(!request.getTestRunTrackerId().equals(resolvedRun.getId()))throw new IllegalArgumentException("The selected Test Run tracker is not shared in the target stream.");
        for(Integer id:request.getTargetTestCaseIds())positive(id,"targetTestCaseId");
    }
    private TrackerDto requiredTracker(Integer id){TrackerDto t=trackerDao.findById(id);if(t==null)throw new IllegalArgumentException("Tracker "+id+" was not found.");return t;}
    private void validateProject(TrackerDto tracker,Integer expected){try{Method gp=tracker.getClass().getMethod("getProject");Object p=gp.invoke(tracker);if(p==null)return;Object actual=p.getClass().getMethod("getId").invoke(p);if(actual instanceof Number&&((Number)actual).intValue()!=expected)throw new IllegalArgumentException("Tracker "+tracker.getId()+" does not belong to project "+expected+".");}catch(NoSuchMethodException ignored){}catch(ReflectiveOperationException e){throw new IllegalStateException("Unable to validate tracker project membership.",e);}}
    private void positive(Integer value,String name){if(value==null||value<=0)throw new IllegalArgumentException(name+" must be a positive integer.");}
}
