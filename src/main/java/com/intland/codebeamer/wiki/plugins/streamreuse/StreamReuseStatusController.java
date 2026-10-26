package com.intland.codebeamer.wiki.plugins.streamreuse;
import java.util.Collections;import java.util.Map;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/custom/stream-test-run-reuse")
public class StreamReuseStatusController {
 private final StreamReuseFacade facade;public StreamReuseStatusController(StreamReuseFacade f){facade=f;}
 @GetMapping("/status/{id}") public ResponseEntity<?> status(@PathVariable String id){StreamReuseModels.Report r=facade.status(id);return r==null?ResponseEntity.status(HttpStatus.NOT_FOUND).body(Collections.singletonMap("error","Operation not found")):ResponseEntity.ok(r);}
}
