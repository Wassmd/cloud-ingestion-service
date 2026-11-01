package cloudingestion;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
@Component
public class PromotionPriceParser {

  private final JsonMapper jsonMapper;

  /** Parses and validates one JSONL line. Never throws for bad data. */
  public ParsedLine parseLine(String line, int lineNumber) {
    PromotionPrice p;
    try {
      p = jsonMapper.readValue(line, PromotionPrice.class);
    } catch (JacksonException e) {
      return new ParsedLine.Invalid(lineNumber, "invalid JSON - " + e.getOriginalMessage());
    }

    if (p.productId() == null || p.price() == null || p.startDate() == null || p.endDate() == null) {
      return new ParsedLine.Invalid(lineNumber, "productId, price, startDate and endDate are required");
    }
    if (p.price().compareTo(BigDecimal.ZERO) < 0) {
      return new ParsedLine.Invalid(lineNumber, "price must not be negative");
    }
    if (p.endDate().isBefore(p.startDate())) {
      return new ParsedLine.Invalid(lineNumber, "endDate is before startDate");
    }
    return new ParsedLine.Valid(lineNumber, p);
  }
}
