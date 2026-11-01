package cloudingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Eventarc Cloud Storage events. Any non-2xx response makes Eventarc
 * retry the delivery, so 2xx is returned on success (also when single invalid
 * lines were skipped) or for files that are intentionally ignored.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/events/gcs/file-received")
public class IngestionRestController {

  private final IngestionService ingestionService;

  @PostMapping
  public ResponseEntity<?> ingest(
      @RequestHeader(value = "ce-id", required = false) String eventId,
      @RequestHeader(value = "ce-type", required = false) String eventType,
      @RequestBody StorageObjectEvent event) {

    log.info("Received event id={} type={} object=gs://{}/{}", eventId, eventType, event.bucket(), event.name());

    if (event.bucket() == null || event.name() == null) {
      return ResponseEntity.badRequest().body("Event must contain 'bucket' and 'name'");
    }
    if (!event.name().endsWith(".jsonl")) {
      log.info("Skipping non-JSONL object {}", event.name());
      return ResponseEntity.noContent().build();
    }

    return ResponseEntity.ok(ingestionService.ingest(event.bucket(), event.name()));
  }

  @ExceptionHandler(InvalidIngestionFileException.class)
  public ResponseEntity<String> handleInvalidFile(InvalidIngestionFileException e) {
    log.error("Invalid ingestion file: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(e.getMessage());
  }
}
