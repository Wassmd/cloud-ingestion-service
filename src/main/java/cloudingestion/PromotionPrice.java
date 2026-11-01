package cloudingestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PromotionPrice(Integer productId, BigDecimal price, OffsetDateTime startDate, OffsetDateTime endDate) {
}
