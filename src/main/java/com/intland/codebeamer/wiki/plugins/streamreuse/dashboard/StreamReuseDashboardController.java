package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import com.intland.codebeamer.persistence.dto.UserDto;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamReuseFacade;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamReuseModels;
import com.intland.codebeamer.wiki.plugins.streamreuse.StreamReuseParameters;

@RestController
@RequestMapping("/streamReuse/philips")
public class StreamReuseDashboardController {
    private static final Logger LOG = LogManager.getLogger(StreamReuseDashboardController.class);
    private final StreamReuseFacade facade;
    private final DashboardParameterMapper mapper;
    private final DashboardRequestValidator validator;
    private final DashboardCurrentUserResolver userResolver;

    public StreamReuseDashboardController(StreamReuseFacade facade, DashboardParameterMapper mapper,
            DashboardRequestValidator validator, DashboardCurrentUserResolver userResolver) {
        this.facade=facade; this.mapper=mapper; this.validator=validator; this.userResolver=userResolver;
    }

    @RequestMapping(value="/preview",method=RequestMethod.POST,consumes="application/json",produces="application/json")
    public java.util.Map<String, Object> preview(HttpServletRequest servletRequest,@RequestBody DashboardReuseRequest request) {
        validator.validate(servletRequest,request); UserDto user=userResolver.resolve(servletRequest);
        StreamReuseParameters parameters=mapper.toParameters(request,true);
        LOG.info("STREAM_REUSE_DASHBOARD_PREVIEW userId={}, sourceStreamId={}, targetStreamId={}, selectedTargets={}",
                user.getId(),request.getSourceStreamId(),request.getTargetStreamId(),request.getTargetTestCaseIds().size());
        return DashboardReportResponse.from(facade.prepare(servletRequest,user,parameters));
    }

    @RequestMapping(value="/execute",method=RequestMethod.POST,consumes="application/json",produces="application/json")
    public java.util.Map<String, Object> execute(HttpServletRequest servletRequest,@RequestBody DashboardReuseRequest request) {
        validator.validate(servletRequest,request); UserDto user=userResolver.resolve(servletRequest);
        StreamReuseParameters parameters=mapper.toParameters(request,false);
        LOG.info("STREAM_REUSE_DASHBOARD_EXECUTE userId={}, operationKey={}",user.getId(),request.getOperationKey());
        return DashboardReportResponse.from(facade.prepare(servletRequest,user,parameters));
    }

    @RequestMapping(value="/status/{operationId}",method=RequestMethod.GET,produces="application/json")
    public ResponseEntity<java.util.Map<String, Object>> status(@PathVariable("operationId") String operationId) {
        StreamReuseModels.Report report=facade.status(operationId);
        return report==null?new ResponseEntity<>(HttpStatus.NOT_FOUND):new ResponseEntity<>(DashboardReportResponse.from(report),HttpStatus.OK);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e){return new ResponseEntity<>(new ErrorResponse("VALIDATION_ERROR",e.getMessage()),HttpStatus.BAD_REQUEST);}
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> failure(IllegalStateException e){LOG.error("STREAM_REUSE_DASHBOARD_FAILED",e);return new ResponseEntity<>(new ErrorResponse("REUSE_ERROR",e.getMessage()),HttpStatus.INTERNAL_SERVER_ERROR);}
    public static final class ErrorResponse {private final String code,message;public ErrorResponse(String code,String message){this.code=code;this.message=message;}public String getCode(){return code;}public String getMessage(){return message;}}
}
