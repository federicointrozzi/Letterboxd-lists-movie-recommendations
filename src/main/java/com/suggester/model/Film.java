package com.suggester.model;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A film as identified by Letterboxd. The slug is the canonical identity;
 * everything else is display metadata scraped alongside it.
 */
public class Film {

    private static final Pattern YEAR = Pattern.compile("\\((\\d{4})\\)\\s*$");

    private final String slug;
    private final String name;
    private final Integer year;

    public Film(String slug, String rawName) {
        this.slug = slug;
        String n = rawName == null ? "" : rawName.trim();
        Matcher m = YEAR.matcher(n);
        if (m.find()) {
            this.year = Integer.parseInt(m.group(1));
            this.name = n.substring(0, m.start()).trim();
        } else {
            this.year = null;
            this.name = n;
        }
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public Integer getYear() {
        return year;
    }

    /** Public Letterboxd page for this film. */
    public String getUrl() {
        return "https://letterboxd.com/film/" + slug + "/";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Film other)) return false;
        return Objects.equals(slug, other.slug);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slug);
    }

    @Override
    public String toString() {
        return name + (year != null ? " (" + year + ")" : "") + " [" + slug + "]";
    }
}
