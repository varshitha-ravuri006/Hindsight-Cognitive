package com.vishwas.matching;

import java.util.List;

/**
 * Output of one matcher run.
 *
 * @param exactMatches  books rows that matched a GSTR-2B row on GSTIN + normalised number with no difference
 * @param booksRows     books invoice rows considered
 * @param gstr2bRows    GSTR-2B invoice rows considered
 */
public record MatchResult(int exactMatches, int booksRows, int gstr2bRows, List<Finding> findings) {
}
