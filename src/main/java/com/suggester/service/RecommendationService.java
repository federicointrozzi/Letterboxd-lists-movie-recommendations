package com.suggester.service;

import com.suggester.config.CacheManager;
import com.suggester.model.Film;
import com.suggester.model.ListSnapshot;
import com.suggester.model.Suggestion;
import com.suggester.model.SuggestionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Ranks films by how often they show up in the "similar films" sets of the
 * films already in a list -- the same idea as playlist recommendations.
 *
 * Matches near the top of a similar list count for more than matches at the
 * bottom, since Letterboxd returns them in relevance order.
 */
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    /** How many suggestions we return. */
    private static final int MAX_RESULTS = 30;

    /** A bottom-of-the-list match is worth this fraction of a top one. */
    private static final double TAIL_WEIGHT = 0.5;

    /** Source films named per suggestion, to keep the payload sane. */
    private static final int MAX_BECAUSE_OF = 6;

    private final LetterboxdClient client;
    private final CacheManager cache;

    public RecommendationService(LetterboxdClient client, CacheManager cache) {
        this.client = client;
        this.cache = cache;
    }

    public SuggestionResponse suggest(String listUrl) {
        long start = System.currentTimeMillis();

        ListSnapshot snapshot = client.getList(listUrl);
        List<Film> listFilms = snapshot.films();
        if (listFilms.isEmpty()) {
            return new SuggestionResponse(
                    snapshot.name(), 0, snapshot.complete(), 0,
                    System.currentTimeMillis() - start, List.of());
        }

        Map<String, List<Film>> similarBySlug = fetchSimilarInParallel(listFilms);

        Set<String> alreadyInList = new HashSet<>();
        for (Film f : listFilms) alreadyInList.add(f.getSlug());

        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (Film source : listFilms) {
            List<Film> similar = similarBySlug.getOrDefault(source.getSlug(), List.of());
            for (int i = 0; i < similar.size(); i++) {
                Film candidate = similar.get(i);
                if (alreadyInList.contains(candidate.getSlug())) continue;

                double weight = 1.0 - (TAIL_WEIGHT * i / Math.max(1, similar.size() - 1));
                tallies.computeIfAbsent(candidate.getSlug(), k -> new Tally(candidate))
                        .add(weight, source.getName());
            }
        }

        List<Tally> ranked = new ArrayList<>(tallies.values());
        ranked.sort(Comparator
                .comparingDouble((Tally t) -> t.score).reversed()
                .thenComparing(t -> -t.matches)
                .thenComparing(t -> t.film.getName()));

        List<Suggestion> top = new ArrayList<>();
        for (Tally t : ranked.subList(0, Math.min(MAX_RESULTS, ranked.size()))) {
            top.add(new Suggestion(
                    t.film.getSlug(),
                    t.film.getName(),
                    t.film.getYear(),
                    t.film.getUrl(),
                    "/api/poster/" + t.film.getSlug(),
                    t.matches,
                    Math.round(t.score * 100.0) / 100.0,
                    t.becauseOf()));
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("{} films -> {} candidates -> top {} in {} ms",
                listFilms.size(), tallies.size(), top.size(), elapsed);

        return new SuggestionResponse(
                snapshot.name(),
                listFilms.size(),
                snapshot.complete(),
                tallies.size(),
                elapsed,
                top);
    }

    /** Resolves a poster URL, going to Letterboxd only on a cache miss. */
    public String posterUrl(String slug) {
        return cache.poster(slug, client::getPosterUrl);
    }

    /**
     * One request per list film, run on virtual threads. The client's own
     * semaphore is what actually limits pressure on Letterboxd.
     */
    private Map<String, List<Film>> fetchSimilarInParallel(List<Film> films) {
        Map<String, List<Film>> results = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Film film : films) {
                futures.add(pool.submit(() ->
                        results.put(film.getSlug(), cache.similar(film.getSlug(), client::getSimilar))));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("A similar-films lookup failed: {}", e.getMessage());
                }
            }
        }

        long empty = results.values().stream().filter(List::isEmpty).count();
        if (empty > 0) {
            log.warn("{} of {} films returned no similar films", empty, films.size());
        }
        return results;
    }

    /** One list film that pointed at a candidate, and how strongly. */
    private record Source(String name, double weight) {
    }

    /** Running total for one candidate film. */
    private static final class Tally {
        final Film film;
        double score;
        int matches;

        /**
         * Min-heap of the strongest sources seen so far. Keeping only the best
         * few bounds memory, and means the explanation names the films that
         * actually drove this suggestion rather than whichever came first.
         */
        private final PriorityQueue<Source> best =
                new PriorityQueue<>(Comparator.comparingDouble(Source::weight));

        Tally(Film film) {
            this.film = film;
        }

        void add(double weight, String sourceName) {
            score += weight;
            matches++;
            best.add(new Source(sourceName, weight));
            if (best.size() > MAX_BECAUSE_OF) best.poll();
        }

        List<String> becauseOf() {
            return best.stream()
                    .sorted(Comparator.comparingDouble(Source::weight).reversed())
                    .map(Source::name)
                    .toList();
        }
    }
}
