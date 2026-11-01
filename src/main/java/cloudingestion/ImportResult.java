package cloudingestion;

import java.util.List;

/**
 * Summary of a file import. {@code errors} contains only the first few invalid
 * lines to keep the response small; {@code skipped} is the total count.
 */
public record ImportResult(String sourceFile, int imported, int skipped, List<ParsedLine.Invalid> errors) {
}
