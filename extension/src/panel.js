/**
 * The results panel, rendered inside a shadow root.
 *
 * Letterboxd's class names are exactly what changes when they redesign, so we
 * ship our own styles in their palette rather than borrowing theirs. The shadow
 * boundary also guarantees our CSS cannot leak into their page.
 */
window.LBX = window.LBX || {};

LBX.panel = (() => {
  const CSS = `
    :host { display: block; margin: 0 0 28px; }
    .box {
      background: #14181c; border: 1px solid #2c3440;
      border-radius: 8px; padding: 18px 20px;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      color: #9ab;
    }
    .head {
      display: flex; align-items: baseline; gap: 10px;
      flex-wrap: wrap; margin-bottom: 14px;
    }
    .title { color: #fff; font-size: 1.05rem; font-weight: 700; margin: 0; }
    .meta { color: #678; font-size: .82rem; flex: 1; }
    .close {
      background: none; border: 0; color: #678; cursor: pointer;
      font-size: 1.1rem; line-height: 1; padding: 2px 4px;
    }
    .close:hover { color: #fff; }
    .bar {
      height: 3px; background: #2c3440; border-radius: 2px;
      overflow: hidden; margin-bottom: 14px;
    }
    .bar > i { display: block; height: 100%; background: #00e054; width: 0; transition: width .2s; }
    .warn {
      background: #3a3320; border: 1px solid #b8912f; color: #e8c46a;
      padding: 10px 14px; border-radius: 6px; font-size: .84rem; margin-bottom: 14px;
    }
    .err {
      background: #401a1a; border: 1px solid #e05050; color: #ff9090;
      padding: 10px 14px; border-radius: 6px; font-size: .88rem;
    }
    .grid {
      display: grid; gap: 16px;
      grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
    }
    .card { position: relative; }
    .poster {
      position: relative; display: block; aspect-ratio: 2 / 3;
      border-radius: 5px; overflow: hidden;
      background: #2c3440; border: 1px solid #456;
      transition: border-color .15s, transform .15s;
    }
    .card:hover .poster { border-color: #00e054; transform: translateY(-2px); }
    .poster img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .rank {
      position: absolute; top: 5px; left: 5px; z-index: 2;
      background: rgba(20,24,28,.88); color: #fff;
      font-size: .68rem; font-weight: 700; padding: 2px 6px; border-radius: 3px;
    }
    .hits {
      position: absolute; bottom: 5px; right: 5px; z-index: 2;
      background: #00e054; color: #14181c;
      font-size: .68rem; font-weight: 700; padding: 2px 6px; border-radius: 3px;
    }
    .seen {
      position: absolute; bottom: 5px; left: 5px; z-index: 2;
      background: rgba(20,24,28,.88); color: #40bcf4;
      font-size: .64rem; font-weight: 700; letter-spacing: .04em;
      padding: 2px 6px; border-radius: 3px;
    }
    .card.-seen .poster img { opacity: .55; }
    .card.-seen:hover .poster img { opacity: 1; }
    .filter {
      display: inline-flex; align-items: center; gap: 6px;
      background: #2c3440; border: 1px solid #456; color: #9ab;
      border-radius: 14px; padding: 4px 12px; font-size: .76rem;
      cursor: pointer; user-select: none;
    }
    .filter:hover { border-color: #678; color: #fff; }
    .filter.-on { background: #00e054; border-color: #00e054; color: #14181c; font-weight: 600; }
    .name {
      display: block; color: #fff; font-size: .82rem; font-weight: 600;
      margin: 7px 0 1px; line-height: 1.28; text-decoration: none;
    }
    .name:hover { color: #00e054; }
    .year { color: #678; font-size: .74rem; }
    .why { color: #556; font-size: .7rem; margin-top: 4px; line-height: 1.35; }
  `;

  let host = null;
  let root = null;

  function mount(anchor) {
    if (host && host.isConnected) return root;
    host = document.createElement("div");
    host.id = "lbx-suggester-panel";
    root = host.attachShadow({ mode: "open" });
    const style = document.createElement("style");
    style.textContent = CSS;
    root.append(style, document.createElement("div"));
    anchor.insertAdjacentElement("beforebegin", host);
    return root;
  }

  function body() {
    return root.querySelector("div");
  }

  function unmount() {
    if (host) host.remove();
    host = null;
    root = null;
  }

  function showProgress(label, fraction) {
    body().innerHTML = `
      <div class="box">
        <div class="head">
          <p class="title">Finding suggestions</p>
          <span class="meta">${escapeHtml(label)}</span>
        </div>
        <div class="bar"><i style="width:${Math.round(fraction * 100)}%"></i></div>
      </div>`;
  }

  function showError(message) {
    body().innerHTML = `
      <div class="box">
        <div class="head"><p class="title">Suggestions</p></div>
        <div class="err">${escapeHtml(message)}</div>
      </div>`;
  }

  let current = null;
  let hideSeen = false;

  function showResults(result, onClose) {
    current = result;
    hideSeen = false;
    if (!result.pool.length) {
      showError("No suggestions found for this list.");
      return;
    }
    render(onClose);
  }

  /**
   * Draws from the ranked pool rather than a fixed slice, so hiding watched
   * films pulls the next ones up instead of leaving gaps in the grid.
   */
  function render(onClose) {
    const { pool, candidates, listSize, elapsedMs, complete, maxResults, seenCount } = current;

    const visible = (hideSeen ? pool.filter((s) => !s.seen) : pool).slice(0, maxResults);
    const secs = (elapsedMs / 1000).toFixed(1);

    body().innerHTML = `
      <div class="box">
        <div class="head">
          <p class="title">Suggestions (${visible.length})</p>
          <span class="meta">${listSize} films &middot; ${candidates} candidates &middot; ${secs}s</span>
          <button class="close" title="Hide">&times;</button>
        </div>
        ${complete ? "" : `<div class="warn">Part of the list could not be read,
          so a suggestion below may already be on it.</div>`}
        ${seenCount > 0
          ? `<div style="margin-bottom:14px">
               <span class="filter${hideSeen ? " -on" : ""}">
                 ${hideSeen ? "&#10003; " : ""}Hide films I have seen (${seenCount})
               </span>
             </div>`
          : ""}
        <div class="grid">${visible.map(card).join("")}</div>
      </div>`;

    body().querySelector(".close").addEventListener("click", () => {
      unmount();
      if (onClose) onClose();
    });

    const filter = body().querySelector(".filter");
    if (filter) {
      filter.addEventListener("click", () => {
        hideSeen = !hideSeen;
        render(onClose);
      });
    }

    lazyLoadPosters();
  }

  function card(s, i) {
    const href = `/film/${s.slug}/`;
    return `
      <div class="card${s.seen ? " -seen" : ""}">
        <a class="poster" href="${href}" data-slug="${escapeHtml(s.slug)}">
          <span class="rank">${i + 1}</span>
          ${s.seen ? '<span class="seen">SEEN</span>' : ""}
          <span class="hits">${s.matches}</span>
          <img alt="" src="${LBX.letterboxd.EMPTY_POSTER}">
        </a>
        <a class="name" href="${href}">${escapeHtml(s.name)}</a>
        ${s.year ? `<div class="year">${s.year}</div>` : ""}
        <div class="why">from ${escapeHtml(s.becauseOf.slice(0, 3).join(", "))}</div>
      </div>`;
  }

  /**
   * Posters cost one film-page fetch each, so we resolve them only as cards
   * scroll into view -- a run of 30 suggestions usually needs far fewer.
   */
  function lazyLoadPosters() {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const anchor = entry.target;
          observer.unobserve(anchor);
          const slug = anchor.dataset.slug;
          LBX.store
            .through(`poster:${slug}`, "poster", () =>
              LBX.letterboxd.getPosterUrl(slug)
            )
            .then((url) => {
              const img = anchor.querySelector("img");
              if (img && url) img.src = url;
            });
        }
      },
      { rootMargin: "300px" }
    );
    body()
      .querySelectorAll(".poster")
      .forEach((el) => observer.observe(el));
  }

  function escapeHtml(s) {
    return String(s == null ? "" : s).replace(
      /[&<>"']/g,
      (c) =>
        ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
    );
  }

  return { mount, unmount, showProgress, showResults, showError };
})();
