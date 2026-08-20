/**
 * Ranks films by how often they appear in the "similar films" sets of the films
 * already on a list -- the same idea as playlist recommendations.
 *
 * Matches near the top of a similar list count for more than matches at the
 * bottom, since Letterboxd returns them in relevance order.
 *
 * Films the member has already watched are marked, not dropped: this is a tool
 * for curating a list, and a film you have seen and loved is exactly the kind of
 * thing that belongs on one. Hiding them is a choice made in the panel.
 */
window.LBX = window.LBX || {};

LBX.recommend = (() => {
  /** A bottom-of-the-list match is worth this fraction of a top one. */
  const TAIL_WEIGHT = 0.5;

  /** Source films named per suggestion, on the card's "from" line. */
  const MAX_BECAUSE_OF = 6;

  /**
   * Ranked entries handed to the panel. Larger than what is shown, so filtering
   * out watched films still leaves a full grid instead of a thinning one.
   */
  const POOL_FACTOR = 4;
  const POOL_MIN = 100;

  /**
   * @param listFilms films already on the list, which are never suggested back
   * @param watchedSlugs films the member has seen; marked, not excluded
   * @param onProgress called with (done, total) as similar sets come in
   */
  async function build(listFilms, watchedSlugs, maxResults, onProgress) {
    const onList = new Set(listFilms.map((f) => f.slug));
    const watched = new Set(watchedSlugs);

    const tallies = new Map();
    let done = 0;

    await Promise.all(
      listFilms.map(async (source) => {
        const key = `similar:${source.slug}`;
        const similar = await LBX.store.through(key, "similar", () =>
          LBX.letterboxd.getSimilar(source.slug)
        );

        const last = Math.max(1, similar.length - 1);
        similar.forEach((candidate, i) => {
          if (onList.has(candidate.slug)) return;

          const weight = 1 - (TAIL_WEIGHT * i) / last;
          let t = tallies.get(candidate.slug);
          if (!t) {
            t = { film: candidate, score: 0, matches: 0, sources: [] };
            tallies.set(candidate.slug, t);
          }
          t.score += weight;
          t.matches++;
          // Keep only the strongest sources, so the "from" line names the films
          // that actually drove this suggestion rather than whichever came first.
          t.sources.push({ name: source.name, weight });
          if (t.sources.length > MAX_BECAUSE_OF * 4) {
            t.sources.sort((a, b) => b.weight - a.weight);
            t.sources.length = MAX_BECAUSE_OF;
          }
        });

        done++;
        if (onProgress) onProgress(done, listFilms.length);
      })
    );

    const ranked = [...tallies.values()].sort(
      (a, b) =>
        b.score - a.score ||
        b.matches - a.matches ||
        a.film.name.localeCompare(b.film.name)
    );

    const poolSize = Math.max(maxResults * POOL_FACTOR, POOL_MIN);
    const pool = ranked.slice(0, poolSize).map((t) => ({
      ...t.film,
      matches: t.matches,
      score: Math.round(t.score * 100) / 100,
      seen: watched.has(t.film.slug),
      becauseOf: t.sources
        .sort((a, b) => b.weight - a.weight)
        .slice(0, MAX_BECAUSE_OF)
        .map((s) => s.name),
    }));

    return {
      candidates: tallies.size,
      seenCount: pool.filter((s) => s.seen).length,
      pool,
    };
  }

  return { build };
})();
