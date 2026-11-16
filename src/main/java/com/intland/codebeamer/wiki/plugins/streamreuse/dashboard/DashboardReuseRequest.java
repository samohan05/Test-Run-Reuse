package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import java.util.ArrayList;
import java.util.List;

/** JSON request accepted by the dashboard preview and execute endpoints. */
public class DashboardReuseRequest {
    private Integer projectId;
    private Integer logicalTestCaseTrackerId;
    private Integer sourceStreamId;
    private Integer sourceTestCaseTrackerId;
    private Integer targetStreamId;
    private Integer targetTestCaseTrackerId;
    private Integer testRunTrackerId;
    private List<Integer> targetTestCaseIds = new ArrayList<>();
    private String reuseMode = "latest";
    private Boolean diagnostic = Boolean.TRUE;
    private String operationKey;

    public Integer getProjectId() { return projectId; }
    public void setProjectId(Integer projectId) { this.projectId = projectId; }
    public Integer getLogicalTestCaseTrackerId() { return logicalTestCaseTrackerId; }
    public void setLogicalTestCaseTrackerId(Integer value) { this.logicalTestCaseTrackerId = value; }
    public Integer getSourceStreamId() { return sourceStreamId; }
    public void setSourceStreamId(Integer sourceStreamId) { this.sourceStreamId = sourceStreamId; }
    public Integer getSourceTestCaseTrackerId() { return sourceTestCaseTrackerId; }
    public void setSourceTestCaseTrackerId(Integer value) { this.sourceTestCaseTrackerId = value; }
    public Integer getTargetStreamId() { return targetStreamId; }
    public void setTargetStreamId(Integer targetStreamId) { this.targetStreamId = targetStreamId; }
    public Integer getTargetTestCaseTrackerId() { return targetTestCaseTrackerId; }
    public void setTargetTestCaseTrackerId(Integer value) { this.targetTestCaseTrackerId = value; }
    public Integer getTestRunTrackerId() { return testRunTrackerId; }
    public void setTestRunTrackerId(Integer testRunTrackerId) { this.testRunTrackerId = testRunTrackerId; }
    public List<Integer> getTargetTestCaseIds() { return targetTestCaseIds; }
    public void setTargetTestCaseIds(List<Integer> value) {
        this.targetTestCaseIds = value == null ? new ArrayList<>() : value;
    }
    public String getReuseMode() { return reuseMode; }
    public void setReuseMode(String reuseMode) { this.reuseMode = reuseMode; }
    public Boolean getDiagnostic() { return diagnostic; }
    public void setDiagnostic(Boolean diagnostic) { this.diagnostic = diagnostic; }
    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String operationKey) { this.operationKey = operationKey; }
}
