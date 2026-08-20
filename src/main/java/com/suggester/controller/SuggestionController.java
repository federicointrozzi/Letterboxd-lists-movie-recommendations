package com.suggester.controller;

import com.suggester.model.SuggestionResponse;
import com.suggester.service.LetterboxdClient;
import com.suggester.service.RecommendationService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

public class SuggestionController {

    private static final Logger log = LoggerFactory.getLogger(SuggestionController.class);

    /** Accepts lists, watchlists and any other letterboxd.com film grid. */
    private static final Pattern LIST_URL = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?letterboxd\\.com/[^/]+/(?:list/[^/]+|watchlist|films)(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,120}$");

    private final RecommendationService recommender;

    public SuggestionController(RecommendationService recommender) {
        this.recommender = recommender;
    }

    public void suggest(Context ctx) {
        SuggestionRequest request;
        try {
            request = ctx.bodyAsClass(SuggestionRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid request body"));
            return;
        }

        String listUrl = request == null ? null : request.listUrl();
        if (listUrl == null || listUrl.isBlank()) {
            ctx.status(400).json(Map.of("error", "A list URL is required"));
            return;
        }

        listUrl = listUrl.trim();
        if (!LIST_URL.matcher(listUrl).matches()) {
            ctx.status(400).json(Map.of("error",
                    "This must be a public Letterboxd list, for example "
                    + "https://letterboxd.com/username/list/list-name/"));
            return;
        }

        try {
            SuggestionResponse response = recommender.suggest(listUrl);
            ctx.json(response);
        } catch (Exception e) {
            log.error("Suggestion failed for {}", listUrl, e);
            ctx.status(502).json(Map.of("error",
                    "Letterboxd did not respond as expected. Please try again shortly."));
        }
    }

    /**
     * Redirects to the film's poster on Letterboxd's CDN. Going through us lets
     * the browser load posters progressively while the result cards are already
     * on screen, and keeps the resolved URLs cached server-side.
     */
    public void poster(Context ctx) {
        String slug = ctx.pathParam("slug");
        if (!SLUG.matcher(slug).matches()) {
            ctx.redirect(LetterboxdClient.emptyPoster());
            return;
        }
        ctx.header("Cache-Control", "public, max-age=86400");
        ctx.redirect(recommender.posterUrl(slug));
    }

    public record SuggestionRequest(String listUrl) {
    }
}
