package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;
import java.util.LinkedHashMap;import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;import org.springframework.web.bind.annotation.RequestMethod;import org.springframework.web.bind.annotation.RestController;
@RestController @RequestMapping("/streamReuse/philips")
public class DashboardApiMappingProbeController {
 @RequestMapping(value="/ping",method=RequestMethod.GET,produces="application/json")
 public Map<String,Object> ping(){Map<String,Object> r=new LinkedHashMap<>();r.put("status","UP");r.put("component","StreamTestRunReuseDashboardBackend");r.put("version","1.2.1");r.put("mapping","/cb/api/streamReuse/philips");r.put("timeFormat","ISO-8601 string");return r;}
}
