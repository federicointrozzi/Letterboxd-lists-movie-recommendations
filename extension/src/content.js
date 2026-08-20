/**
 * Wires the feature into a Letterboxd list page: a button in the list header,
 * and the results panel directly above the film grid so they are visible
 * without scrolling past a list that may run to hundreds of films.
 */
(() => {
  const BUTTON_ID = "lbx-suggest-button";

  function injectButton() {
    if (document.getElementById(BUTTON_ID)) return;

    const grid = document.querySelector(LBX.letterboxd.LIST_GRID);
    if (!grid) return; // not a page with a film grid

    const header =
      document.querySelector(".list-title-intro") ||
      document.querySelector("h1.title-1, h1.title-4");
    if (!header) return;

    const button = document.createElement("button");
    button.id = BUTTON_ID;
    button.type = "button";
    button.textContent = "Suggest similar films";
    Object.assign(button.style, {
      background: "#00e054",
      color: "#14181c",
      border: "0",
      borderRadius: "5px",
      padding: "8px 16px",
      font: "600 13px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
      cursor: "pointer",
      marginTop: "12px",
    });
    button.addEventListener("click", () => run(button));
    header.insertAdjacentElement("afterend", button);
  }

  async function run(button) {
    const grid = document.querySelector(LBX.letterboxd.LIST_GRID);
    if (!grid) return;

    button.disabled = true;
    button.style.opacity = "0.6";
    button.textContent = "Working...";

    LBX.panel.mount(grid);
    LBX.panel.showProgress("reading the list", 0);

    try {
      const settings = await LBX.store.settings();
      const listUrl = LBX.letterboxd.currentListUrl();
      const started = Date.now();

      // The whole list, regardless of which page we happen to be on.
      const list = await LBX.store.through(`list:${listUrl}`, "list", () =>
        LBX.letterboxd.crawlGrid(listUrl, (n) =>
          LBX.panel.showProgress(`reading the list (${n} films)`, 0.1)
        )
      );

      if (!list.films.length) {
        LBX.panel.showError(
          "Could not read any films from this list. If it is private, make sure you are signed in."
        );
        return;
      }

      // Loaded to mark suggestions, not to drop them -- the panel decides
      // whether to hide them, and can change its mind without re-running.
      const watched = settings.useWatchedData ? await loadWatched(settings) : [];

      LBX.panel.showProgress(`analysing ${list.films.length} films`, 0.2);
      const result = await LBX.recommend.build(
        list.films,
        watched,
        settings.maxResults,
        (done, total) =>
          LBX.panel.showProgress(
            `analysing ${done}/${total} films`,
            0.2 + 0.8 * (done / total)
          )
      );

      LBX.panel.showResults({
        ...result,
        listSize: list.films.length,
        maxResults: settings.maxResults,
        complete: list.complete,
        elapsedMs: Date.now() - started,
      });
    } catch (e) {
      LBX.panel.showError(`Something went wrong: ${e.message}`);
    } finally {
      button.disabled = false;
      button.style.opacity = "1";
      button.textContent = "Suggest similar films";
    }
  }

  /**
   * The signed-in member's watched films, used to mark suggestions they have
   * already seen. Detection of the username is best-effort; the settings popup
   * holds a manual override for when the page markup does not give it up.
   */
  async function loadWatched(settings) {
    const username = settings.username || LBX.letterboxd.detectUsername();
    if (!username) return [];

    LBX.panel.showProgress("reading your watched films", 0.15);
    const watched = await LBX.store.through(
      `watched:${username}`,
      "watched",
      () =>
        LBX.letterboxd
          .crawlGrid(`/${username}/films/`, (n) =>
            LBX.panel.showProgress(`reading your watched films (${n})`, 0.15)
          )
          .then((r) => r.films.map((f) => f.slug))
    );
    return watched;
  }

  injectButton();

  // Letterboxd swaps page content in without a full reload, so re-attach when
  // the grid is replaced.
  new MutationObserver(() => injectButton()).observe(document.body, {
    childList: true,
    subtree: true,
  });
})();
