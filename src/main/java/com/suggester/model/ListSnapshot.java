package com.suggester.model;

import java.util.List;

/**
 * A list as scraped in one pass.
 *
 * @param complete false when a page could not be read, meaning some films on the
 *                 list are unknown to us and could wrongly show up as suggestions
 */
public record ListSnapshot(String name, List<Film> films, boolean complete) {
}
