const DEFAULTS = { maxResults: 30, useWatchedData: true, username: "" };

const useWatchedData = document.getElementById("useWatchedData");
const maxResults = document.getElementById("maxResults");
const username = document.getElementById("username");
const saved = document.getElementById("saved");

function flash(message) {
  saved.textContent = message;
  setTimeout(() => (saved.textContent = ""), 1600);
}

async function load() {
  const bag = await chrome.storage.local.get("settings");
  const s = { ...DEFAULTS, ...(bag.settings || {}) };
  useWatchedData.checked = s.useWatchedData;
  maxResults.value = s.maxResults;
  username.value = s.username;
}

async function save() {
  const bag = await chrome.storage.local.get("settings");
  const settings = {
    ...DEFAULTS,
    ...(bag.settings || {}),
    useWatchedData: useWatchedData.checked,
    maxResults: Number(maxResults.value) || DEFAULTS.maxResults,
    username: username.value.trim().replace(/^\/+|\/+$/g, ""),
  };
  await chrome.storage.local.set({ settings });
  flash("Saved");
}

for (const el of [useWatchedData, maxResults, username]) {
  el.addEventListener("change", save);
}

document.getElementById("clear").addEventListener("click", async () => {
  const all = await chrome.storage.local.get(null);
  const keys = Object.keys(all).filter((k) => k !== "settings");
  if (keys.length) await chrome.storage.local.remove(keys);
  flash(`Cleared ${keys.length} entries`);
});

load();
