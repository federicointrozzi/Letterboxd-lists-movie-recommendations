package com.suggester.model;

import java.util.List;

/**
 * One recommended film, with the evidence behind it.
 *
 * @param matches  how many films in the list listed this as similar
 * @param score    match count weighted by position in each similar list
 * @param becauseOf a capped sample of the list films that pointed here
 */
public record Suggestion(
        String slug,
        String name,
        Integer year,
        String url,
        String posterUrl,
        int matches,
        double score,
        List<String> becauseOf
) {
}
