package cloud_ingestion_service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestion")
public class IngestionRestController {

  @PostMapping
  public ResponseEntity<String> ingestData() {
    // Implement your ingestion logic here
    return ResponseEntity.ok("Data ingested successfully");
  }
}
