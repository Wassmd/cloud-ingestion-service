package cloudingestion;

/** Result of parsing a single JSONL line: either a valid record or an error. */
public sealed interface ParsedLine {

  int lineNumber();

  record Valid(
      int lineNumber,
      PromotionPrice price) implements ParsedLine { }

  record Invalid(
      int lineNumber,
      String error
  ) implements ParsedLine { }
}
