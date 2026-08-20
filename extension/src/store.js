/**
 * Persistent cache and settings, on top of chrome.storage.local.
 *
 * Three lifetimes, for three kinds of data: similar-film sets drift slowly,
 * poster URLs essentially never change, and a member's watched list changes
 * whenever they log a film.
 */
window.LBX = window.LBX || {};

LBX.store = (() => {
  const TTL = {
    similar: 24 * 60 * 60 * 1000,
    poster: 30 * 24 * 60 * 60 * 1000,
    watched: 12 * 60 * 60 * 1000,
    list: 60 * 60 * 1000,
  };

  const DEFAULTS = {
    maxResults: 30,
    // Whether to look up which films the member has seen. Suggestions are then
    // marked, never dropped -- hiding them is a toggle in the results panel.
    useWatchedData: true,
    username: "",
  };

  async function get(key) {
    const bag = await chrome.storage.local.get(key);
    const entry = bag[key];
    if (!entry) return null;
    if (entry.expires && Date.now() > entry.expires) {
      chrome.storage.local.remove(key);
      return null;
    }
    return entry.value;
  }

  async function put(key, value, kind) {
    const expires = TTL[kind] ? Date.now() + TTL[kind] : null;
    await chrome.storage.local.set({ [key]: { value, expires } });
  }

  /** Reads through the cache, calling loader only on a miss. */
  async function through(key, kind, loader) {
    const hit = await get(key);
    if (hit !== null) return hit;
    const value = await loader();
    await put(key, value, kind);
    return value;
  }

  async function settings() {
    const bag = await chrome.storage.local.get("settings");
    return { ...DEFAULTS, ...(bag.settings || {}) };
  }

  async function saveSettings(patch) {
    const current = await settings();
    await chrome.storage.local.set({ settings: { ...current, ...patch } });
  }

  /** Drops cached pages but keeps the user's settings. */
  async function clearCache() {
    const all = await chrome.storage.local.get(null);
    const keys = Object.keys(all).filter((k) => k !== "settings");
    if (keys.length) await chrome.storage.local.remove(keys);
    return keys.length;
  }

  return { get, put, through, settings, saveSettings, clearCache, DEFAULTS };
})();
