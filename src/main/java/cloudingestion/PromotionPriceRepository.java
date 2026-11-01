package cloudingestion;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Repository
public class PromotionPriceRepository {

  private static final int BATCH_SIZE = 500;

  // Each file is its own version (history). A redelivered event for the same
  // file updates the existing rows instead of creating duplicates.
  private static final String UPSERT_SQL = """
      INSERT INTO promotion.promotion_price (product_id, price, start_date, end_date, source_file)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (product_id, start_date, source_file) DO UPDATE
      SET price    = EXCLUDED.price,
          end_date = EXCLUDED.end_date
      """;

  private final JdbcTemplate jdbcTemplate;

  @Transactional
  public void upsertAll(List<PromotionPrice> prices, String sourceFile) {
    jdbcTemplate.batchUpdate(UPSERT_SQL, prices, BATCH_SIZE, (ps, p) -> {
      ps.setInt(1, p.productId());
      ps.setBigDecimal(2, p.price());
      ps.setObject(3, toUtc(p.startDate()));
      ps.setObject(4, toUtc(p.endDate()));
      ps.setString(5, sourceFile);
    });
  }

  // Columns are TIMESTAMP (without time zone), so values are stored as UTC.
  private static Object toUtc(OffsetDateTime dateTime) {
    return dateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
