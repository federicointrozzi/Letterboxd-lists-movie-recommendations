package com.suggester.service;

import com.suggester.model.Film;
import com.suggester.model.ListSnapshot;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-HTTP access to Letterboxd.
 *
 * Letterboxd serves these pages fine without a browser as long as the request
 * carries a real User-Agent -- the default Java one gets refused. A semaphore
 * caps how hard we hit the site regardless of how many callers are active.
 */
public class LetterboxdClient {

    private static final Logger log = LoggerFactory.getLogger(LetterboxdClient.class);

    private static final String BASE = "https://letterboxd.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Concurrent requests in flight against letterboxd.com, process-wide. */
    private static final int MAX_CONCURRENT = 16;

    /** Guards against a runaway list; 100 films per page means 5000 films. */
    private static final int MAX_LIST_PAGES = 50;

    /** Attempts per URL before a page counts as genuinely unavailable. */
    private static final int MAX_ATTEMPTS = 3;

    /** Consecutive unavailable pages that end pagination. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final String EMPTY_POSTER =
            "https://s.ltrbxd.com/static/img/empty-poster-150-DtnLDE3k.png";

    private static final Pattern JSON_LD_IMAGE =
            Pattern.compile("\"image\"\\s*:\\s*\"(https://a\\.ltrbxd\\.com/resized/[^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Semaphore gate = new Semaphore(MAX_CONCURRENT);

    /**
     * Fetches a page body, retrying transient failures. Returns empty only once
     * the page has genuinely failed -- callers treat that as missing data, so a
     * network blip must not be mistaken for it.
     */
    public Optional<String> fetch(String url) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<String> body = fetchOnce(url);
            if (body.isPresent()) return body;
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        log.debug("Giving up on {} after {} attempts", url, MAX_ATTEMPTS);
        return Optional.empty();
    }

    private Optional<String> fetchOnce(String url) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("{} returned {}", url, response.statusCode());
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Request failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        } finally {
            gate.release();
        }
    }

    /**
     * Every film in a public list or watchlist, following pagination.
     * An out-of-range page comes back as a 200 with no films, which is our stop signal.
     */
    public ListSnapshot getList(String listUrl) {
        String base = normalize(listUrl);
        List<Film> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String name = null;

        boolean complete = true;
        int consecutiveFailures = 0;

        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            String pageUrl = page == 1 ? base : base + "page/" + page + "/";
            Optional<String> body = fetch(pageUrl);

            if (body.isEmpty()) {
                // Page 1 failing means the list is private, gone, or mistyped.
                if (page == 1) return new ListSnapshot("Letterboxd list", List.of(), false);

                // Later pages: skip rather than stop, so films we cannot read do
                // not silently become suggestable. Only an empty page ends a list.
                log.warn("List page {} unavailable; continuing", page);
                complete = false;
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("{} pages failed in a row; stopping at {} films",
                            consecutiveFailures, all.size());
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;

            Document doc = Jsoup.parse(body.get());
            if (page == 1) name = cleanTitle(doc.title());

            List<Film> onPage = parsePosters(doc);
            if (onPage.isEmpty()) break;

            int added = 0;
            for (Film f : onPage) {
                if (seen.add(f.getSlug())) {
                    all.add(f);
                    added++;
                }
            }
            // A page that repeats what we already have means pagination wrapped around.
            if (added == 0) break;
        }

        log.info("List {} -> {} films{}", base, all.size(), complete ? "" : " (incomplete)");
        return new ListSnapshot(name == null ? "Letterboxd list" : name, all, complete);
    }

    /** Drops the site suffix and the bidi marks Letterboxd puts in page titles. */
    private String cleanTitle(String title) {
        if (title == null) return "Letterboxd list";
        String t = title
                .replaceAll("[\u200e\u200f\u202a-\u202e]", "")
                .replaceAll("\\s*[\u2022\u00b7]\\s*Letterboxd\\s*$", "")
                .trim();
        return t.isBlank() ? "Letterboxd list" : t;
    }

    /**
     * The films Letterboxd considers similar to this one, in relevance order.
     * The dedicated /similar/ page carries far more of them than the film's main page.
     */
    public List<Film> getSimilar(String slug) {
        return fetch(BASE + "/film/" + slug + "/similar/")
                .map(body -> parsePosters(Jsoup.parse(body)))
                .orElseGet(List::of);
    }

    /**
     * The real poster URL for a film, read from the JSON-LD on its page.
     * It cannot be derived from the slug: Letterboxd stores posters under two
     * different path schemes and one of them is an opaque hash.
     */
    public String getPosterUrl(String slug) {
        Optional<String> body = fetch(BASE + "/film/" + slug + "/");
        if (body.isEmpty()) return EMPTY_POSTER;

        Matcher m = JSON_LD_IMAGE.matcher(body.get());
        if (m.find()) {
            return m.group(1).replace("\\/", "/");
        }
        log.debug("No poster found for {}", slug);
        return EMPTY_POSTER;
    }

    public static String emptyPoster() {
        return EMPTY_POSTER;
    }

    /**
     * Reads the lazy-loaded poster components that Letterboxd uses for every
     * film grid -- list pages and similar pages share the same markup.
     */
    private List<Film> parsePosters(Document doc) {
        List<Film> films = new ArrayList<>();
        for (Element el : doc.select("div.react-component[data-component-class=LazyPoster]")) {
            String slug = el.attr("data-item-slug");
            String name = el.attr("data-item-name");
            if (!slug.isEmpty() && !name.isEmpty()) {
                films.add(new Film(slug, name));
            }
        }
        return films;
    }

    /** Strips sort/display suffixes so we can append page/N/ predictably. */
    private String normalize(String listUrl) {
        String url = listUrl.trim();
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        if (!url.startsWith("https://")) url = "https://" + url;
        url = url.replaceAll("[?#].*$", "");
        if (!url.endsWith("/")) url += "/";
        url = url.replaceAll("(?:detail|by/[a-z-]+|page/\\d+)/$", "");
        if (!url.endsWith("/")) url += "/";
        return url;
    }
}
