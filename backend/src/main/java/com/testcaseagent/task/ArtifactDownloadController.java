package com.testcaseagent.task;

import java.nio.file.Files;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Serves only validated, completed workbook artifacts by opaque application identifier.
 *
 * [Req-ID]: REQ-EXP-005, REQ-EXP-006, REQ-WEB-005
 */
@RestController
@RequestMapping("/api/artifacts")
public final class ArtifactDownloadController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final GenerationTaskRepository repository;

    public ArtifactDownloadController(GenerationTaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String artifactId) {
        GenerationTaskRepository.StoredArtifact artifact = repository.findReadyArtifact(artifactId)
                .filter(candidate -> Files.isRegularFile(candidate.path()))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Artifact not found"));
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("test-cases.xlsx")
                        .build().toString())
                .body(new FileSystemResource(artifact.path()));
    }
}
