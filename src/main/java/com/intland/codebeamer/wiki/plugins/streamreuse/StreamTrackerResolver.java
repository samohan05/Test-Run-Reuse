package com.intland.codebeamer.wiki.plugins.streamreuse;
import com.intland.codebeamer.persistence.dao.TrackerDao; import com.intland.codebeamer.persistence.dto.TrackerDto; import jakarta.servlet.http.HttpServletRequest; import org.springframework.stereotype.Component;
@Component
public class StreamTrackerResolver {
 private final StreamMetadataClient streams; private final TrackerDao trackerDao;
 public StreamTrackerResolver(StreamMetadataClient streams,TrackerDao trackerDao){this.streams=streams;this.trackerDao=trackerDao;}
 public Resolution resolve(HttpServletRequest req,StreamReuseParameters p){
  StreamReuseModels.EntityInfo ss=streams.findStream(req,p.sourceStreamId), ts=streams.findStream(req,p.targetStreamId);
  TrackerDto sourceTc=required(p.sourceTestCaseTrackerId,"source Test Case tracker"), sourceTr=required(p.sourceTestRunTrackerId,"source Test Run tracker");
  StreamReuseModels.EntityInfo targetTcRef=streams.resolveTrackerVariant(req,p.targetStreamId,p.sourceTestCaseTrackerId); TrackerDto targetTc=required(targetTcRef.getId(),"target Test Case tracker");
  TrackerDto targetTr; String strategy;
  try {StreamReuseModels.EntityInfo targetTrRef=streams.resolveTrackerVariant(req,p.targetStreamId,p.sourceTestRunTrackerId);targetTr=required(targetTrRef.getId(),"target Test Run tracker");strategy=targetTr.getId().equals(sourceTr.getId())?"SHARED_TRACKER":"STREAM_VARIANT";} catch(Exception ex){targetTr=sourceTr;strategy="SHARED_TRACKER_FALLBACK";}
  return new Resolution(ss,ts,sourceTc,targetTc,sourceTr,targetTr,strategy);
 }
 private TrackerDto required(int id,String label){TrackerDto t=trackerDao.findById(id);if(t==null)throw new IllegalArgumentException(label+" "+id+" was not found");return t;}
 public static final class Resolution {public final StreamReuseModels.EntityInfo sourceStream,targetStream;public final TrackerDto sourceTestCaseTracker,targetTestCaseTracker,sourceTestRunTracker,targetTestRunTracker;public final String targetRunStrategy; Resolution(StreamReuseModels.EntityInfo a,StreamReuseModels.EntityInfo b,TrackerDto c,TrackerDto d,TrackerDto e,TrackerDto f,String s){sourceStream=a;targetStream=b;sourceTestCaseTracker=c;targetTestCaseTracker=d;sourceTestRunTracker=e;targetTestRunTracker=f;targetRunStrategy=s;}}
}
