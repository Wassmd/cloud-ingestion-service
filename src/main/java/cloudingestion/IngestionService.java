package cloudingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;

@Slf4j
@RequiredArgsConstructor
@Service
public class IngestionService {

  static final int BATCH_SIZE = 500;
  static final int MAX_REPORTED_ERRORS = 20;

  private final Storage storage;
  private final PromotionPriceParser parser;
  private final PromotionPriceRepository repository;

  /**
   * Streams the JSONL file from the bucket line by line. Invalid lines are
   * skipped and logged; valid lines are upserted in batches (one transaction
   * per batch). Re-processing the same file is safe thanks to the upsert.
   */
  public ImportResult ingest(String bucket, String objectName) {
    String sourceFile = "gs://" + bucket + "/" + objectName;
    try (BufferedReader reader = new BufferedReader(
        Channels.newReader(storage.reader(BlobId.of(bucket, objectName)), StandardCharsets.UTF_8))) {
      return ingest(reader, sourceFile);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + sourceFile, e);
    }
  }

  ImportResult ingest(BufferedReader reader, String sourceFile) throws IOException {
    ImportRun run = new ImportRun(sourceFile);
    int lineNumber = 0;

    String line;
    while ((line = reader.readLine()) != null) {
      lineNumber++;
      if (!line.isBlank()) {
        run.accept(parser.parseLine(line, lineNumber));
      }
    }
    return run.finish();
  }

  /** Collects parsed lines of one file and writes the valid ones in batches. */
  private final class ImportRun {

    private final String sourceFile;
    private final List<PromotionPrice> pending = new ArrayList<>(BATCH_SIZE);
    private final List<ParsedLine.Invalid> errors = new ArrayList<>();
    private int imported = 0;
    private int skipped = 0;

    private ImportRun(String sourceFile) {
      this.sourceFile = sourceFile;
    }

    void accept(ParsedLine parsed) {
      switch (parsed) {
        case ParsedLine.Valid valid -> addValid(valid.price());
        case ParsedLine.Invalid invalid -> addInvalid(invalid);
      }
    }

    private void addValid(PromotionPrice price) {
      pending.add(price);
      if (pending.size() == BATCH_SIZE) {
        flush();
      }
    }

    private void addInvalid(ParsedLine.Invalid invalid) {
      skipped++;
      log.warn("Skipping line {} of {}: {}", invalid.lineNumber(), sourceFile, invalid.error());
      if (errors.size() < MAX_REPORTED_ERRORS) {
        errors.add(invalid);
      }
    }

    private void flush() {
      if (pending.isEmpty()) {
        return;
      }
      repository.upsertAll(List.copyOf(pending), sourceFile);
      imported += pending.size();
      pending.clear();
    }

    ImportResult finish() {
      flush();
      if (imported == 0 && skipped > 0) {
        ParsedLine.Invalid first = errors.getFirst();
        throw new InvalidIngestionFileException("No valid lines in " + sourceFile + " (" + skipped
            + " invalid), first error: line " + first.lineNumber() + ": " + first.error());
      }
      log.info("Imported {} promotion prices from {}, skipped {} invalid lines", imported, sourceFile, skipped);
      return new ImportResult(sourceFile, imported, skipped, errors);
    }
  }
}
