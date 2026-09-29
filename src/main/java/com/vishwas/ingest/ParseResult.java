package com.vishwas.ingest;

import java.util.List;

/** Parsed lines plus human-readable warnings about individual rows (bad GSTIN, tax heads that look wrong...). */
public record ParseResult(List<ParsedLine> lines, List<String> warnings) {
}
