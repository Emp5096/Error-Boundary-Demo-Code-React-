package com.xy.interview.demo.oom;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping({"/api/oom", "/api/lab"})
public class OomLabController {

    private final OomLabService oomLabService;

    public OomLabController(OomLabService oomLabService) {
        this.oomLabService = oomLabService;
    }

    @GetMapping("/status")
    public OomExperimentView status() {
        return oomLabService.status();
    }

    @PostMapping("/experiments")
    public ResponseEntity<OomExperimentView> start(@RequestBody OomExperimentRequest request) {
        return ResponseEntity.accepted().body(oomLabService.start(request));
    }

    @DeleteMapping("/experiments/current")
    public OomExperimentView stop() {
        return oomLabService.stop();
    }

    @GetMapping("/evidence/{type}")
    public EvidenceView collectEvidence(@PathVariable String type) {
        return oomLabService.collectEvidence(type);
    }

    @PostMapping("/diagnoses")
    public DiagnosisResult diagnose(@RequestBody DiagnosisRequest request) {
        return oomLabService.submitDiagnosis(request);
    }

    @GetMapping("/probe")
    public ProbeView probe() {
        return oomLabService.replayProbe();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", error.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> experimentConflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "EXPERIMENT_RUNNING", error.getMessage(), Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp) {
    }
}
