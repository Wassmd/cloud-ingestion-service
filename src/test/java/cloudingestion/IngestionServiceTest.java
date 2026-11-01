package cloudingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.cloud.storage.Storage;

import tools.jackson.databind.json.JsonMapper;

class IngestionServiceTest {

  private static final String FILE = "gs://bucket/prices.jsonl";

  private final PromotionPriceRepository repository = mock(PromotionPriceRepository.class);
  private final IngestionService service = new IngestionService(
      mock(Storage.class), new PromotionPriceParser(JsonMapper.builder().build()), repository);

  private static String validLine(int productId) {
    return "{\"productId\": " + productId
        + ", \"price\": 1.99, \"startDate\": \"2026-11-02T00:00:00Z\", \"endDate\": \"2026-11-08T00:00:00Z\"}";
  }

  private ImportResult ingest(String content) throws Exception {
    return service.ingest(new BufferedReader(new StringReader(content)), FILE);
  }

  @Test
  void skipsInvalidLinesAndImportsTheRest() throws Exception {
    ImportResult result = ingest(String.join("\n",
        validLine(1),
        "{broken",
        "",
        validLine(2),
        "{\"productId\": 3}"));

    assertThat(result.imported()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(2);
    assertThat(result.errors()).extracting(ParsedLine.Invalid::lineNumber).containsExactly(2, 5);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PromotionPrice>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).upsertAll(captor.capture(), eq(FILE));
    assertThat(captor.getValue()).extracting(PromotionPrice::productId).containsExactly(1, 2);
  }

  @Test
  void writesInBatches() throws Exception {
    String content = IntStream.rangeClosed(1, IngestionService.BATCH_SIZE + 1)
        .mapToObj(IngestionServiceTest::validLine)
        .collect(Collectors.joining("\n"));

    ImportResult result = ingest(content);

    assertThat(result.imported()).isEqualTo(IngestionService.BATCH_SIZE + 1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PromotionPrice>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository, times(2)).upsertAll(captor.capture(), anyString());
    assertThat(captor.getAllValues()).extracting(List::size).containsExactly(IngestionService.BATCH_SIZE, 1);
  }

  @Test
  void limitsReportedErrors() throws Exception {
    String content = validLine(1) + "\n" + "{broken\n".repeat(IngestionService.MAX_REPORTED_ERRORS + 5);

    ImportResult result = ingest(content);

    assertThat(result.skipped()).isEqualTo(IngestionService.MAX_REPORTED_ERRORS + 5);
    assertThat(result.errors()).hasSize(IngestionService.MAX_REPORTED_ERRORS);
  }

  @Test
  void failsWhenNoLineIsValid() {
    assertThatThrownBy(() -> ingest("{broken\n{\"productId\": 1}"))
        .isInstanceOf(InvalidIngestionFileException.class)
        .hasMessageContaining("No valid lines")
        .hasMessageContaining("2 invalid");
    verify(repository, never()).upsertAll(anyList(), anyString());
  }

  @Test
  void emptyFileImportsNothing() throws Exception {
    assertThat(ingest("")).isEqualTo(new ImportResult(FILE, 0, 0, List.of()));
    verify(repository, never()).upsertAll(anyList(), anyString());
  }
}
