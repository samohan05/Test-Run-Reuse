package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intland.codebeamer.wiki.plugins.streamreuse.StreamReuseParameters;

/** Maps dashboard JSON to the existing Wiki plug-in parameter contract. */
@Component
public class DashboardParameterMapper {

    public StreamReuseParameters toParameters(
            DashboardReuseRequest request,
            boolean dryRun) {

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceStreamId", request.getSourceStreamId());
        values.put("targetStreamId", request.getTargetStreamId());
        values.put("sourceTestCaseTrackerId", request.getSourceTestCaseTrackerId());
        values.put("sourceTestRunTrackerId", request.getTestRunTrackerId());
        values.put("targetTestCaseIds", csv(request));
        values.put("reuseMode", request.getReuseMode());
        values.put("diagnostic", Boolean.TRUE.equals(request.getDiagnostic()));
        values.put("dryRun", dryRun);
        values.put("operationKey", dryRun ? "" : request.getOperationKey());

        return StreamReuseParameters.parse(values);
    }

    private String csv(DashboardReuseRequest request) {
        return request.getTargetTestCaseIds().stream()
                .filter(id -> id != null)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
