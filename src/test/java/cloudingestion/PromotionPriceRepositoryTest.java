package cloudingestion;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@JdbcTest
@AutoConfigureEmbeddedDatabase(provider = EMBEDDED)
@Import(PromotionPriceRepository.class)
class PromotionPriceRepositoryTest {

  @Autowired
  private PromotionPriceRepository repository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private static PromotionPrice price(String value) {
    return new PromotionPrice(4711, new BigDecimal(value),
        OffsetDateTime.parse("2026-11-02T01:00:00+01:00"), OffsetDateTime.parse("2026-11-08T23:59:59Z"));
  }

  private int rowCount() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM promotion.promotion_price", Integer.class);
  }

  @Test
  void redeliveredFileUpdatesInsteadOfDuplicating() {
    repository.upsertAll(List.of(price("1.99")), "gs://b/day1.jsonl");
    repository.upsertAll(List.of(price("1.49")), "gs://b/day1.jsonl");

    assertThat(rowCount()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("SELECT price FROM promotion.promotion_price", BigDecimal.class))
        .isEqualByComparingTo("1.49");
  }

  @Test
  void newFileKeepsPriceHistory() {
    repository.upsertAll(List.of(price("1.99")), "gs://b/day1.jsonl");
    repository.upsertAll(List.of(price("1.49")), "gs://b/day2.jsonl");

    assertThat(rowCount()).isEqualTo(2);
  }

  @Test
  void storesTimestampsAsUtc() {
    repository.upsertAll(List.of(price("1.99")), "gs://b/day1.jsonl");

    assertThat(jdbcTemplate.queryForObject("SELECT start_date::text FROM promotion.promotion_price", String.class))
        .isEqualTo("2026-11-02 00:00:00");
  }
}
