/**
 * Reading Letterboxd from inside a Letterboxd tab.
 *
 * Every request here is same-origin, so it needs no CORS handling and carries
 * the member's own session -- which is what lets us see private lists and their
 * watched films. Requests go out from their browser at their own pace.
 *
 * SELECTORS: everything that depends on Letterboxd's markup lives in this file,
 * so a redesign on their side is a one-file fix.
 */
window.LBX = window.LBX || {};

LBX.letterboxd = (() => {
  const POSTER = 'div.react-component[data-component-class="LazyPoster"]';
  const LIST_GRID = "ul.poster-list";
  const MAX_PAGES = 50;
  const MAX_ATTEMPTS = 3;
  const MAX_CONSECUTIVE_FAILURES = 3;
  const CONCURRENCY = 8;

  const EMPTY_POSTER =
    "https://s.ltrbxd.com/static/img/empty-poster-150-DtnLDE3k.png";

  const JSON_LD_IMAGE =
    /"image"\s*:\s*"(https:\/\/a\.ltrbxd\.com\/resized\/[^"]+)"/;

  /**
   * Caps how many requests are in flight at once. We are a guest on someone
   * else's site: a burst that looks like a crawler helps nobody.
   */
  function createGate(limit) {
    let active = 0;
    const queue = [];
    const pump = () => {
      if (active >= limit || queue.length === 0) return;
      active++;
      const { job, resolve, reject } = queue.shift();
      job().then(resolve, reject).finally(() => {
        active--;
        pump();
      });
    };
    return (job) =>
      new Promise((resolve, reject) => {
        queue.push({ job, resolve, reject });
        pump();
      });
  }

  const gate = createGate(CONCURRENCY);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /** Fetches a page as a parsed document, retrying transient failures. */
  async function fetchDoc(url) {
    return gate(async () => {
      for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
          const res = await fetch(url, {
            credentials: "same-origin",
            headers: { Accept: "text/html" },
          });
          if (res.ok) {
            const text = await res.text();
            return new DOMParser().parseFromString(text, "text/html");
          }
          // A missing page is an answer, not a glitch worth retrying.
          if (res.status === 404 || res.status === 403) return null;
        } catch (e) {
          // Network hiccup; fall through to the retry.
        }
        if (attempt < MAX_ATTEMPTS) await sleep(300 * attempt);
      }
      return null;
    });
  }

  /** Films in any Letterboxd poster grid, in page order. */
  function parsePosters(doc) {
    const films = [];
    for (const el of doc.querySelectorAll(POSTER)) {
      const slug = el.getAttribute("data-item-slug");
      const raw = el.getAttribute("data-item-name");
      if (!slug || !raw) continue;
      const m = raw.match(/\((\d{4})\)\s*$/);
      films.push({
        slug,
        name: m ? raw.slice(0, m.index).trim() : raw.trim(),
        year: m ? Number(m[1]) : null,
      });
    }
    return films;
  }

  /**
   * Walks a paginated grid (a list, a watchlist, a member's watched films).
   * An empty page ends it; a page that fails is skipped rather than treated as
   * the end, so films we could not read do not quietly become suggestable.
   */
  async function crawlGrid(baseUrl, onProgress) {
    const base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    const films = [];
    const seen = new Set();
    let complete = true;
    let consecutiveFailures = 0;

    for (let page = 1; page <= MAX_PAGES; page++) {
      const url = page === 1 ? base : `${base}page/${page}/`;
      const doc = await fetchDoc(url);

      if (!doc) {
        if (page === 1) return { films: [], complete: false };
        complete = false;
        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break;
        continue;
      }
      consecutiveFailures = 0;

      const onPage = parsePosters(doc);
      if (onPage.length === 0) break;

      let added = 0;
      for (const f of onPage) {
        if (seen.has(f.slug)) continue;
        seen.add(f.slug);
        films.push(f);
        added++;
      }
      if (added === 0) break; // pagination wrapped around

      if (onProgress) onProgress(films.length);
    }

    return { films, complete };
  }

  /** The films Letterboxd considers similar to this one, in relevance order. */
  async function getSimilar(slug) {
    const doc = await fetchDoc(`/film/${slug}/similar/`);
    return doc ? parsePosters(doc) : [];
  }

  /**
   * A film's real poster. It cannot be derived from the slug -- Letterboxd
   * stores posters under two different path schemes and one is an opaque hash,
   * so the JSON-LD on the film page is the only reliable source.
   */
  async function getPosterUrl(slug) {
    const res = await gate(() =>
      fetch(`/film/${slug}/`, { credentials: "same-origin" }).catch(() => null)
    );
    if (!res || !res.ok) return EMPTY_POSTER;
    const html = await res.text();
    const m = html.match(JSON_LD_IMAGE);
    return m ? m[1].replace(/\\\//g, "/") : EMPTY_POSTER;
  }

  /**
   * The username of whoever is signed in.
   *
   * NOTE: this reads the account nav, which we could not inspect without a
   * signed-in session. Several shapes are tried; if all miss, the caller falls
   * back to the username saved in the extension's settings.
   */
  function detectUsername() {
    const candidates = [
      '.js-nav-account a[href^="/"]',
      "#nav-account a[href^='/']",
      ".main-nav .profile-mini-person a[href^='/']",
      'header a.navitem[href$="/films/"]',
    ];
    for (const sel of candidates) {
      const el = document.querySelector(sel);
      const href = el && el.getAttribute("href");
      const m = href && href.match(/^\/([^/]+)\//);
      if (m && m[1]) return m[1];
    }
    return null;
  }

  /** The canonical URL of the list this page belongs to, without pagination. */
  function currentListUrl() {
    return location.pathname
      .replace(/\/(page\/\d+|detail|by\/[a-z-]+)\/?$/g, "/")
      .replace(/\/+$/, "/");
  }

  return {
    fetchDoc,
    parsePosters,
    crawlGrid,
    getSimilar,
    getPosterUrl,
    detectUsername,
    currentListUrl,
    EMPTY_POSTER,
    LIST_GRID,
  };
})();
