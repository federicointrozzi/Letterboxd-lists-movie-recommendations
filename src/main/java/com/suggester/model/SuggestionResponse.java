package com.suggester.model;

import java.util.List;

/**
 * Envelope returned by POST /api/suggest.
 *
 * @param listComplete false when part of the list could not be read, so a
 *                     suggestion might already be on it
 */
public record SuggestionResponse(
        String listName,
        int listSize,
        boolean listComplete,
        int candidatesConsidered,
        long elapsedMs,
        List<Suggestion> suggestions
) {
}
