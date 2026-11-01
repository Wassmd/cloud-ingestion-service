package cloudingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class PromotionPriceParserTest {

  private final PromotionPriceParser parser = new PromotionPriceParser(JsonMapper.builder().build());

  @Test
  void parsesValidLineAndIgnoresUnknownFields() {
    ParsedLine result = parser.parseLine("""
        {"productId": 4711, "price": 1.99, "startDate": "2026-11-02T00:00:00+01:00", "endDate": "2026-11-08T23:59:59Z", "extra": 1}""", 3);

    // offsets are normalized to UTC (same instant)
    assertThat(result).isEqualTo(new ParsedLine.Valid(3, new PromotionPrice(4711, new BigDecimal("1.99"),
        OffsetDateTime.parse("2026-11-01T23:00:00Z"), OffsetDateTime.parse("2026-11-08T23:59:59Z"))));
  }

  @Test
  void reportsInvalidJson() {
    assertThat(parser.parseLine("{not json", 2))
        .isInstanceOfSatisfying(ParsedLine.Invalid.class, i -> {
          assertThat(i.lineNumber()).isEqualTo(2);
          assertThat(i.error()).startsWith("invalid JSON");
        });
  }

  @Test
  void reportsMissingFields() {
    assertThat(parser.parseLine("""
        {"productId": 1, "startDate": "2026-11-02T00:00:00Z", "endDate": "2026-11-03T00:00:00Z"}""", 1))
        .isEqualTo(new ParsedLine.Invalid(1, "productId, price, startDate and endDate are required"));
  }

  @Test
  void reportsNegativePrice() {
    assertThat(parser.parseLine("""
        {"productId": 1, "price": -1, "startDate": "2026-11-02T00:00:00Z", "endDate": "2026-11-03T00:00:00Z"}""", 1))
        .isEqualTo(new ParsedLine.Invalid(1, "price must not be negative"));
  }

  @Test
  void reportsEndDateBeforeStartDate() {
    assertThat(parser.parseLine("""
        {"productId": 1, "price": 1.0, "startDate": "2026-11-05T00:00:00Z", "endDate": "2026-11-03T00:00:00Z"}""", 1))
        .isEqualTo(new ParsedLine.Invalid(1, "endDate is before startDate"));
  }
}
