package com.intland.codebeamer.wiki.plugins.streamreuse;
import com.intland.codebeamer.persistence.dao.TrackerItemDao; import com.intland.codebeamer.persistence.dto.*; import java.util.*; import org.springframework.stereotype.Component;
@Component
public class IdempotencyService {
 private final TrackerItemDao dao; private final EvidencePropagationService evidence; public IdempotencyService(TrackerItemDao d,EvidencePropagationService e){dao=d;evidence=e;}
 public boolean exists(UserDto user,int targetRunTrackerId,int targetTestCaseId,Collection<Integer> originIds){List<TrackerItemDto> runs=dao.findByTracker(targetRunTrackerId);if(runs==null)return false;TrackerDto tracker=null;for(TrackerItemDto r:runs)if(r!=null&&r.getTracker()!=null){tracker=r.getTracker();break;}if(tracker==null)return false;int idx=evidence.index(user,tracker);Set<Integer> wanted=new LinkedHashSet<>(originIds);for(TrackerItemDto r:runs){if(r==null||r.isDeleted()||!links(r,targetTestCaseId))continue;Set<Integer> found=new LinkedHashSet<>();List<?> vals=r.getChoiceList(idx);if(vals!=null)for(Object v:vals)if(v instanceof TrackerItemDto&&((TrackerItemDto)v).getId()!=null)found.add(((TrackerItemDto)v).getId());if(found.equals(wanted)||found.containsAll(wanted))return true;}return false;}
 private boolean links(TrackerItemDto r,int id){List<Map<Integer,String>> rows=r.getTable(0);if(rows==null)return false;for(int i=0;i<rows.size();i++)try{String c=r.getTableCell(0,i,0);int d=c.indexOf('-'),s=c.indexOf('/');if(Integer.valueOf(c.substring(d+1,s))==id)return true;}catch(Exception ignored){}return false;}
}
