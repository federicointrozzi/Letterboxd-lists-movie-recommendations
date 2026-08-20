package com.suggester.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.suggester.model.Film;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Caches the two expensive lookups. Similar-film sets drift slowly, poster URLs
 * essentially never change, so they get very different lifetimes.
 */
public class CacheManager {

    private final Cache<String, List<Film>> similar = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(20_000)
            .build();

    private final Cache<String, String> posters = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.DAYS)
            .maximumSize(50_000)
            .build();

    public List<Film> similar(String slug, Function<String, List<Film>> loader) {
        return similar.get(slug, loader);
    }

    public String poster(String slug, Function<String, String> loader) {
        return posters.get(slug, loader);
    }

    public long similarCached() {
        return similar.estimatedSize();
    }

    public long postersCached() {
        return posters.estimatedSize();
    }
}
