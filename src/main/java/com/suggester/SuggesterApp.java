package com.suggester;

import com.suggester.config.CacheManager;
import com.suggester.controller.SuggestionController;
import com.suggester.service.LetterboxdClient;
import com.suggester.service.RecommendationService;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SuggesterApp {

    private static final Logger log = LoggerFactory.getLogger(SuggesterApp.class);
    private static final int PORT = 7070;

    public static void main(String[] args) {
        LetterboxdClient client = new LetterboxdClient();
        CacheManager cache = new CacheManager();
        RecommendationService recommender = new RecommendationService(client, cache);
        SuggestionController controller = new SuggestionController(recommender);

        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);

        app.get("/", ctx -> ctx.html(HOMEPAGE));
        app.post("/api/suggest", controller::suggest);
        app.get("/api/poster/{slug}", controller::poster);
        app.get("/health", ctx -> ctx.result("OK"));

        app.start(PORT);
        log.info("Letterboxd List Suggester su http://localhost:{}", PORT);
    }

    private static final String HOMEPAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Letterboxd List Suggester</title>
            <style>
              *, *::before, *::after { box-sizing: border-box; }
              body {
                margin: 0;
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #14181c; color: #9ab; min-height: 100vh;
              }
              .wrap { max-width: 1100px; margin: 0 auto; padding: 40px 20px 80px; }
              h1 { color: #fff; font-size: 1.9rem; margin: 0 0 6px; }
              .sub { color: #678; margin: 0 0 28px; }
              form { display: flex; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
              input {
                flex: 1 1 320px; padding: 12px 16px; font-size: 15px;
                border: 1px solid #456; border-radius: 6px;
                background: #2c3440; color: #fff; outline: none;
              }
              input:focus { border-color: #00e054; }
              input::placeholder { color: #678; }
              button {
                padding: 12px 26px; font-size: 15px; font-weight: 600;
                background: #00e054; color: #14181c; border: 0;
                border-radius: 6px; cursor: pointer;
              }
              button:hover:not(:disabled) { background: #00c849; }
              button:disabled { background: #456; color: #9ab; cursor: not-allowed; }
              .examples { font-size: .85rem; color: #556; margin-bottom: 26px; }
              .examples a { color: #678; cursor: pointer; text-decoration: underline; }
              .examples a:hover { color: #00e054; }
              #msg { padding: 14px 0; color: #678; }
              .err {
                background: #401a1a; border: 1px solid #e05050;
                color: #ff9090; padding: 12px 16px; border-radius: 6px;
              }
              .warn {
                background: #3a3320; border: 1px solid #b8912f;
                color: #e8c46a; padding: 12px 16px; border-radius: 6px;
                margin-bottom: 4px; font-size: .9rem;
              }
              .spin {
                display: inline-block; width: 16px; height: 16px;
                border: 2px solid #456; border-top-color: #00e054;
                border-radius: 50%; animation: sp .8s linear infinite;
                margin-right: 8px; vertical-align: -3px;
              }
              @keyframes sp { to { transform: rotate(360deg); } }
              .meta { color: #678; font-size: .9rem; margin: 4px 0 20px; }
              .meta strong { color: #fff; }
              .grid {
                display: grid; gap: 20px;
                grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
              }
              .card { position: relative; }
              .poster {
                position: relative; aspect-ratio: 2 / 3; border-radius: 6px;
                overflow: hidden; background: #2c3440; border: 1px solid #456;
                display: block; transition: border-color .15s, transform .15s;
              }
              .card:hover .poster { border-color: #00e054; transform: translateY(-3px); }
              .poster img { width: 100%; height: 100%; object-fit: cover; display: block; }
              .rank {
                position: absolute; top: 6px; left: 6px; z-index: 2;
                background: rgba(20,24,28,.88); color: #fff;
                font-size: .72rem; font-weight: 700;
                padding: 3px 7px; border-radius: 4px;
              }
              .hits {
                position: absolute; bottom: 6px; right: 6px; z-index: 2;
                background: #00e054; color: #14181c;
                font-size: .72rem; font-weight: 700;
                padding: 3px 7px; border-radius: 4px;
              }
              .name {
                color: #fff; font-size: .9rem; font-weight: 600;
                margin: 9px 0 2px; line-height: 1.3; text-decoration: none; display: block;
              }
              .name:hover { color: #00e054; }
              .year { color: #678; font-size: .8rem; }
              .why { color: #556; font-size: .75rem; margin-top: 5px; line-height: 1.4; }
            </style>
            </head>
            <body>
            <div class="wrap">
              <h1>Letterboxd List Suggester</h1>
              <p class="sub">Paste a public list: I cross-reference the similar films of every title and tell you what is missing.</p>

              <form id="f">
                <input id="u" type="text" placeholder="https://letterboxd.com/username/list/list-name/" required>
                <button id="b" type="submit">Suggest</button>
              </form>
              <p class="examples">
                Try:
                <a data-url="https://letterboxd.com/arinbicer/list/mcu/">an MCU list</a> &middot;
                <a data-url="https://letterboxd.com/crew/list/showdown-camp-classics/">camp classics</a>
              </p>

              <div id="msg"></div>
              <div id="out"></div>
            </div>

            <script>
            const f = document.getElementById('f');
            const u = document.getElementById('u');
            const b = document.getElementById('b');
            const msg = document.getElementById('msg');
            const out = document.getElementById('out');

            document.querySelectorAll('.examples a').forEach(a => {
              a.addEventListener('click', () => { u.value = a.dataset.url; u.focus(); });
            });

            function esc(s) {
              const d = document.createElement('div');
              d.textContent = s == null ? '' : s;
              return d.innerHTML;
            }

            f.addEventListener('submit', async (e) => {
              e.preventDefault();
              const url = u.value.trim();
              if (!url) return;

              out.innerHTML = '';
              b.disabled = true;
              msg.innerHTML = '<span class="spin"></span> Reading the list and cross-referencing similar films...';

              try {
                const res = await fetch('/api/suggest', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ listUrl: url })
                });
                const data = await res.json();
                if (!res.ok) throw new Error(data.error || 'Request failed');

                if (!data.suggestions.length) {
                  msg.innerHTML = '<div class="err">No suggestions. Is the list public and does it contain films?</div>';
                  return;
                }

                msg.innerHTML = data.listComplete ? '' :
                  '<div class="warn">Part of the list could not be read, so a suggestion ' +
                  'below may already be on it. Try again for a complete result.</div>';
                const secs = (data.elapsedMs / 1000).toFixed(1);
                out.innerHTML =
                  '<p class="meta"><strong>' + esc(data.listName) + '</strong> &middot; ' +
                  data.listSize + ' films analysed &middot; ' +
                  data.candidatesConsidered + ' candidates &middot; ' + secs + 's</p>' +
                  '<div class="grid">' + data.suggestions.map((s, i) =>
                    '<div class="card">' +
                      '<a class="poster" href="' + esc(s.url) + '" target="_blank" rel="noopener">' +
                        '<span class="rank">' + (i + 1) + '</span>' +
                        '<span class="hits">' + s.matches + '</span>' +
                        '<img loading="lazy" alt="" src="' + esc(s.posterUrl) + '">' +
                      '</a>' +
                      '<a class="name" href="' + esc(s.url) + '" target="_blank" rel="noopener">' +
                        esc(s.name) + '</a>' +
                      (s.year ? '<div class="year">' + s.year + '</div>' : '') +
                      '<div class="why">from ' + esc(s.becauseOf.slice(0, 3).join(', ')) + '</div>' +
                    '</div>'
                  ).join('') + '</div>';

              } catch (err) {
                msg.innerHTML = '<div class="err">' + esc(err.message) + '</div>';
              } finally {
                b.disabled = false;
              }
            });
            </script>
            </body>
            </html>
            """;
}
