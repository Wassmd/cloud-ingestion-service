package cloudingestion;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IngestionRestController.class)
class IngestionRestControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private IngestionService ingestionService;

  private static final String EVENT = """
      {"bucket": "demo-bucket", "name": "raw/2026/11/01/prices.jsonl", "size": "123", "contentType": "application/jsonl"}
      """;

  @Test
  void importsJsonlFileFromEvent() throws Exception {
    when(ingestionService.ingest("demo-bucket", "raw/2026/11/01/prices.jsonl")).thenReturn(new ImportResult(
        "gs://demo-bucket/raw/2026/11/01/prices.jsonl", 2, 1, List.of(new ParsedLine.Invalid(3, "invalid JSON"))));

    mockMvc.perform(post("/events/gcs/file-received")
            .header("ce-id", "123")
            .header("ce-type", "google.cloud.storage.object.v1.finalized")
            .contentType(MediaType.APPLICATION_JSON)
            .content(EVENT))
        .andExpect(status().isOk())
        .andExpect(content().json("""
            {"sourceFile": "gs://demo-bucket/raw/2026/11/01/prices.jsonl", "imported": 2, "skipped": 1,
             "errors": [{"lineNumber": 3, "error": "invalid JSON"}]}
            """));

    verify(ingestionService).ingest("demo-bucket", "raw/2026/11/01/prices.jsonl");
  }

  @Test
  void skipsNonJsonlFiles() throws Exception {
    mockMvc.perform(post("/events/gcs/file-received")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"bucket": "demo-bucket", "name": "raw/readme.txt"}
                """))
        .andExpect(status().isNoContent());

    verifyNoInteractions(ingestionService);
  }

  @Test
  void rejectsEventWithoutObjectName() throws Exception {
    mockMvc.perform(post("/events/gcs/file-received")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"bucket": "demo-bucket"}
                """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(ingestionService);
  }

  @Test
  void returns422WhenFileHasNoValidLine() throws Exception {
    when(ingestionService.ingest(anyString(), anyString()))
        .thenThrow(new InvalidIngestionFileException("No valid lines"));

    mockMvc.perform(post("/events/gcs/file-received")
            .contentType(MediaType.APPLICATION_JSON)
            .content(EVENT))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().string("No valid lines"));
  }
}
