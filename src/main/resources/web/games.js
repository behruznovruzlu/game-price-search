"use strict";

(() => {
  const byId = id => document.getElementById(id);
  const ui = {
    query: byId("search-query"), form: byId("search-form"), searchButton: byId("search-button"),
    results: byId("search-results"), resultsTitle: byId("results-title"), resultsCount: byId("results-count"),
    searchStatus: byId("search-status"), searchCache: byId("search-cache"), notice: byId("app-notice"),
    savedList: byId("saved-list"), savedCount: byId("saved-count"), savedEmpty: byId("saved-empty"),
    detail: byId("game-detail"), detailTitle: byId("detail-title"), detailStatus: byId("detail-status"),
    detailCache: byId("detail-cache"), detailContent: byId("detail-content"), saveButton: byId("save-button"),
    bestPrice: byId("best-price"), bestStore: byId("best-store"), lowestPrice: byId("lowest-price"), lowestDate: byId("lowest-date"),
    storeFilter: byId("store-filter"), priceFilter: byId("price-filter"), offers: byId("offers-body"),
    offersEmpty: byId("offers-empty"), offerCount: byId("offer-count"),
    healthButton: byId("health-button"), healthDot: byId("health-dot"), healthLabel: byId("health-label"),
    quitButton: byId("quit-button"), watchRefresh: byId("watch-refresh"), watchStatus: byId("watch-status"),
    watchAlerts: byId("watch-alerts"), alertLink: byId("alert-link"), alertCount: byId("alert-count"),
    watchForm: byId("watch-form"), targetPrice: byId("target-price"), watchSave: byId("watch-save"),
    watchImport: byId("watch-import"), watchImportCopy: byId("watch-import-copy"), watchImportButton: byId("watch-import-button"),
    storageNote: byId("watch-storage-note")
  };
  const SAVED_KEY = "game-price-search.saved.v1";
  const MAX_SAVED = 50;
  let saved = [];
  let watchSnapshot = null;
  let watchAvailable = false;
  let watchBusy = false;
  let watchController = null;
  let watchVersion = 0;
  let watchTimer = null;
  let renderedWatchItems = "";
  let legacySaved = [];
  let legacyRaw = null;
  let games = [];
  let selectedGame = null;
  let selectedDetail = null;
  let searchController = null;
  let detailController = null;
  let searchVersion = 0;
  let detailVersion = 0;
  let lastResultButton = null;
  let shuttingDown = false;

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function message(text, success = false) {
    ui.notice.textContent = text;
    ui.notice.classList.toggle("success", success);
    ui.notice.hidden = !text;
  }

  function validId(value) { return /^[1-9][0-9]{0,19}$/.test(String(value ?? "")); }
  function validPrice(value) { return typeof value === "number" && Number.isFinite(value) && value >= 0; }
  function dollars(value) { return validPrice(value) ? `$${value.toLocaleString("en-US", {minimumFractionDigits: 2, maximumFractionDigits: 2})}` : "—"; }
  function priceText(value) { return validPrice(value) ? `${dollars(value)} USD` : "Unavailable"; }

  function dateText(value, withTime = true) {
    const date = new Date(value);
    if (!value || Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat(undefined, withTime
      ? {year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"}
      : {year: "numeric", month: "short", day: "numeric"}).format(date);
  }

  function safeDealUrl(value) {
    try {
      const url = new URL(value);
      if (url.protocol !== "https:" || url.hostname !== "www.cheapshark.com" || url.pathname !== "/redirect"
          || url.username || url.password || url.port || !url.searchParams.get("dealID")) return null;
      url.hash = "";
      return url.href;
    } catch { return null; }
  }

  function normalizeGame(value) {
    if (!value || !validId(value.id) || typeof value.title !== "string" || !value.title.trim()) return null;
    return {id: String(value.id), title: value.title.trim().slice(0, 1000), steamAppId: String(value.steamAppId ?? ""),
      cheapestPrice: value.cheapestPrice, dealUrl: value.dealUrl};
  }

  function normalizeWatchItem(value) {
    const game = normalizeGame(value);
    if (!game) return null;
    return {...game, targetPrice: validPrice(value.targetPrice) ? value.targetPrice : null,
      lastPrice: validPrice(value.lastPrice) ? value.lastPrice : null,
      lastStore: typeof value.lastStore === "string" ? value.lastStore.slice(0, 200) : null,
      lastCheckedAt: dateText(value.lastCheckedAt) ? value.lastCheckedAt : null,
      lastSuccessAt: dateText(value.lastSuccessAt) ? value.lastSuccessAt : null,
      error: typeof value.error === "string" ? value.error.slice(0, 500) : null,
      targetReached: value.targetReached === true, notificationUnread: value.notificationUnread === true};
  }

  function readLegacySaved() {
    try {
      legacyRaw = localStorage.getItem(SAVED_KEY);
      if (!legacyRaw) return;
      if (legacyRaw.length > 100000) throw new Error("Saved list is too large");
      const parsed = JSON.parse(legacyRaw);
      if (!Array.isArray(parsed) || parsed.length > MAX_SAVED) throw new Error("Invalid saved list");
      const ids = new Set();
      legacySaved = parsed.map(normalizeGame).filter(game => {
        if (!game || ids.has(game.id)) return false;
        ids.add(game.id); return true;
      });
      if (legacySaved.length !== parsed.length) {
        legacySaved = [];
        throw new Error("Some saved entries are invalid");
      }
    } catch {
      ui.watchImport.hidden = false;
      ui.watchImportCopy.textContent = "An older browser shortlist could not be read. Its stored data has been kept.";
      ui.watchImportButton.hidden = true;
    }
  }

  function renderImport() {
    if (!legacySaved.length) return;
    const missing = legacySaved.filter(game => !saved.some(item => item.id === game.id)).length;
    ui.watchImport.hidden = false;
    ui.watchImportCopy.textContent = missing
      ? `${legacySaved.length} games were saved in this browser before price tracking was added. Import them into this computer's watchlist; existing targets will be kept.`
      : "Your older saved games are already in this watchlist. Finish the import to remove the old browser copy.";
    ui.watchImportButton.textContent = missing ? "Import saved games" : "Finish import";
    ui.watchImportButton.disabled = !watchAvailable || watchBusy || shuttingDown;
  }

  function setWatchControls() {
    ui.watchRefresh.disabled = !watchAvailable || watchBusy || shuttingDown || !saved.length || watchSnapshot?.checking === true;
    ui.watchRefresh.textContent = shuttingDown ? "Checks paused" : !watchAvailable ? "Check prices" : watchSnapshot?.checking ? "Checking…" : "Check prices";
    ui.watchSave.disabled = !watchAvailable || watchBusy || shuttingDown;
    ui.targetPrice.disabled = !watchAvailable || watchBusy || shuttingDown;
    ui.savedList.querySelectorAll("input, button").forEach(control => {
      control.disabled = !watchAvailable || watchBusy || shuttingDown;
    });
    updateSaveButton();
    renderImport();
  }

  function renderWatchStatus() {
    const unread = saved.filter(game => game.notificationUnread);
    ui.alertCount.textContent = String(unread.length);
    ui.alertLink.hidden = !unread.length;
    ui.alertLink.setAttribute("aria-label", `View ${unread.length} unread price ${unread.length === 1 ? "alert" : "alerts"}`);
    const alertText = unread.length ? `${unread.length} ${unread.length === 1 ? "game reached its" : "games reached their"} target price. See the last successful check below.` : "";
    if (ui.watchAlerts.textContent !== alertText) ui.watchAlerts.textContent = alertText;
    ui.watchAlerts.hidden = !unread.length;
    ui.savedCount.textContent = String(saved.length);
    ui.savedEmpty.hidden = saved.length > 0 || !watchAvailable;
    if (watchAvailable) {
      const lastCycle = dateText(watchSnapshot.lastCycleAt);
      const storageError = typeof watchSnapshot.storageError === "string" ? watchSnapshot.storageError.trim().slice(0, 500) : "";
      const status = storageError ? "The latest watchlist update could not be saved."
        : watchSnapshot.checking ? "Checking saved games…" : lastCycle ? `Last check cycle: ${lastCycle}.` : "Ready for the first price check.";
      if (ui.watchStatus.textContent !== status) ui.watchStatus.textContent = status;
      ui.watchStatus.classList.toggle("watch-error", Boolean(storageError));
      const seconds = Number(watchSnapshot.refreshIntervalSeconds);
      const interval = Number.isFinite(seconds) && seconds >= 60 ? Math.round(seconds / 60) : 30;
      ui.storageNote.textContent = storageError
        ? `The latest watchlist update could not be saved: ${storageError} The last successfully saved targets and prices are retained. Check available disk space and write access, then retry.`
        : `Saved on this computer, across app restarts and port changes. Checks run every ${interval} minutes while the app is running. Alerts appear here when you open this page.`;
      ui.storageNote.classList.toggle("watch-error", Boolean(storageError));
    }
    setWatchControls();
  }

  function targetFromInput(input) {
    if (input.value.trim() === "") return null;
    const price = Number(input.value);
    if (!input.checkValidity() || !validPrice(price) || price > 1000000 || Math.abs(price * 100 - Math.round(price * 100)) > 0.000001) {
      throw new Error("Enter a target from $0 to $1,000,000 USD, using up to two decimal places, or leave it blank.");
    }
    return price;
  }

  function renderSaved(force = false) {
    const signature = JSON.stringify(saved);
    // Background updates must not discard a target price while someone is editing it.
    if (!force && (renderedWatchItems === signature || ui.savedList.contains(document.activeElement))) return;
    renderedWatchItems = signature;
    ui.savedList.replaceChildren();
    saved.forEach((game, index) => {
      const row = el("li", `saved-item${game.notificationUnread ? " has-alert" : ""}`);
      const heading = el("div", "saved-item-heading");
      const open = el("button", "saved-open");
      open.type = "button";
      open.append(el("span", "saved-number", String(index + 1).padStart(2, "0")), document.createTextNode(game.title));
      open.setAttribute("aria-label", `Check offers for ${game.title}`);
      open.addEventListener("click", () => openGame(game, open));
      const remove = el("button", "saved-remove", "×");
      remove.type = "button";
      remove.setAttribute("aria-label", `Remove ${game.title} from watchlist`);
      remove.addEventListener("click", () => mutateWatchlist("/api/watchlist/remove", {id: game.id}, `${game.title} removed from your watchlist.`));
      heading.append(open, remove);
      row.append(heading);
      const latest = validPrice(game.lastPrice)
        ? `${priceText(game.lastPrice)}${game.lastStore ? ` at ${game.lastStore}` : ""}` : "Price not checked yet";
      row.append(el("p", "watch-price", latest));
      const at = dateText(game.lastSuccessAt);
      row.append(el("p", "watch-time", at ? `Price observed ${at}.` : "A successful price check is still needed."));
      if (game.error) row.append(el("p", "watch-error", `Latest check failed: ${game.error}${at ? " The price above is from the previous successful check." : ""}`));
      if (game.lastCheckedAt && game.lastCheckedAt !== game.lastSuccessAt) {
        row.append(el("p", "watch-time", `Last attempted ${dateText(game.lastCheckedAt)}.`));
      }
      if (game.targetReached) row.append(el("p", "watch-reached", "✓ Target met at the last successful check"));
      const form = el("form", "watch-target-form");
      const inputId = `watch-target-${game.id}`;
      const label = el("label", "", "Target USD"); label.htmlFor = inputId;
      const input = el("input");
      input.id = inputId; input.name = "targetPrice"; input.type = "number"; input.min = "0"; input.max = "1000000"; input.step = "0.01";
      input.inputMode = "decimal"; input.placeholder = "No target";
      input.value = game.targetPrice === null ? "" : game.targetPrice.toFixed(2);
      input.setAttribute("aria-label", `Target price in USD for ${game.title}; leave blank for no alert`);
      const save = el("button", "watch-button", "Save"); save.type = "submit";
      save.setAttribute("aria-label", `Save target price for ${game.title}`);
      form.append(label, input, save);
      form.addEventListener("submit", event => {
        event.preventDefault();
        try { saveWatchedGame(game, targetFromInput(input)); }
        catch (error) { message(error.message); input.focus(); }
      });
      row.append(form);
      if (game.notificationUnread) {
        const acknowledge = el("button", "watch-acknowledge", "Mark alert as read"); acknowledge.type = "button";
        acknowledge.setAttribute("aria-label", `Mark the price alert for ${game.title} as read`);
        acknowledge.addEventListener("click", () => mutateWatchlist("/api/watchlist/acknowledge", {id: game.id}, `Price alert for ${game.title} marked as read.`));
        row.append(acknowledge);
      }
      ui.savedList.append(row);
    });
    setWatchControls();
  }

  function updateSaveButton() {
    const isSaved = selectedGame && saved.some(game => game.id === selectedGame.id);
    ui.saveButton.disabled = !selectedGame || !selectedDetail || shuttingDown || !watchAvailable || watchBusy;
    ui.saveButton.textContent = isSaved ? "✓ Edit price watch" : "＋ Watch price";
    ui.saveButton.classList.toggle("is-saved", Boolean(isSaved));
    ui.saveButton.setAttribute("aria-expanded", String(!ui.watchForm.hidden));
  }

  async function loadWatchlist(force = false) {
    if (shuttingDown || (watchBusy && !force)) return;
    watchController?.abort();
    watchController = new AbortController();
    const controller = watchController;
    const version = ++watchVersion;
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const data = await requestJson("/api/watchlist", controller.signal);
      if (version !== watchVersion || shuttingDown) return;
      if (!Array.isArray(data.items) || data.currency !== "USD") throw new Error("The watchlist response could not be read.");
      const next = data.items.map(normalizeWatchItem);
      if (next.length > MAX_SAVED || next.some(item => !item) || new Set(next.map(item => item.id)).size !== next.length) {
        throw new Error("The watchlist response could not be read.");
      }
      saved = next; watchSnapshot = data; watchAvailable = true;
      renderSaved(force); renderWatchStatus();
    } catch (error) {
      if (version !== watchVersion || shuttingDown) return;
      watchAvailable = false;
      ui.watchStatus.textContent = "Watchlist unavailable. Reconnecting…";
      ui.watchStatus.classList.add("watch-error");
      ui.storageNote.classList.add("watch-error");
      ui.storageNote.textContent = "The app could not load its saved watchlist. The prices shown have not been updated. Make sure the app is running; this page will retry.";
      renderWatchStatus();
    } finally { clearTimeout(timeout); }
  }

  async function postWatchlist(url, body) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      return await requestJson(url, controller.signal, {method: "POST", headers: {"Content-Type": "application/json", "X-App-Action": "change"}, body: JSON.stringify(body)});
    } finally { clearTimeout(timeout); }
  }

  async function beginPriceCheck() {
    try { await postWatchlist("/api/watchlist/refresh", {}); }
    catch (error) { if (error.status !== 409) throw error; }
  }

  async function mutateWatchlist(url, body, successText, refresh = false) {
    if (shuttingDown || watchBusy || !watchAvailable) return false;
    watchBusy = true; setWatchControls();
    let savedSuccessfully = false;
    try {
      await postWatchlist(url, body);
      savedSuccessfully = true;
      if (refresh) {
        try { await beginPriceCheck(); }
        catch (error) { message(`${successText} The price check could not start: ${error.message}`); return true; }
      }
      message(successText, true);
      return true;
    } catch (error) {
      message(`The watchlist change could not be confirmed: ${error.message}`);
      return false;
    } finally {
      await loadWatchlist(true);
      watchBusy = false; setWatchControls();
      if (savedSuccessfully && !watchAvailable) message("Your change was saved, but the updated watchlist could not be loaded. This page will retry.");
    }
  }

  async function saveWatchedGame(game, targetPrice) {
    if (!saved.some(item => item.id === game.id) && saved.length >= MAX_SAVED) {
      message(`Your watchlist holds up to ${MAX_SAVED} games. Remove one before saving another.`); return;
    }
    const success = await mutateWatchlist("/api/watchlist", {id: game.id, title: game.title, targetPrice}, `${game.title} saved to your watchlist.`, true);
    if (success && selectedGame?.id === game.id) { ui.watchForm.hidden = true; updateSaveButton(); }
  }

  async function refreshWatchPrices() {
    if (shuttingDown || watchBusy || !watchAvailable) return;
    watchBusy = true; setWatchControls();
    try { await beginPriceCheck(); message("A price check is running. Results and alerts will appear in your watchlist.", true); }
    catch (error) { message(`The price check could not start: ${error.message}`); }
    finally { await loadWatchlist(true); watchBusy = false; setWatchControls(); }
  }

  async function importLegacySaved() {
    if (shuttingDown || watchBusy || !watchAvailable || !legacySaved.length) return;
    watchBusy = true; setWatchControls();
    try {
      await loadWatchlist(true);
      if (!watchAvailable) throw new Error("The computer's watchlist is unavailable.");
      const missing = legacySaved.filter(game => !saved.some(item => item.id === game.id));
      if (saved.length + missing.length > MAX_SAVED) throw new Error(`The combined list exceeds ${MAX_SAVED} games. Remove some watched games before importing.`);
      for (const game of missing) {
        await postWatchlist("/api/watchlist", {id: game.id, title: game.title, targetPrice: null});
      }
      await loadWatchlist(true);
      if (!watchAvailable || legacySaved.some(game => !saved.some(item => item.id === game.id))) {
        throw new Error("Not every imported game could be confirmed. Your old browser copy has been kept; you can retry.");
      }
      // Keep a newer copy if an older app tab changed the shortlist during import.
      if (localStorage.getItem(SAVED_KEY) === legacyRaw) localStorage.removeItem(SAVED_KEY);
      legacySaved = []; legacyRaw = null; ui.watchImport.hidden = true;
      message("Your older saved games are now in this computer's watchlist. You can set target prices below.", true);
      try { await beginPriceCheck(); }
      catch (error) { message(`Your games were imported, but the price check could not start: ${error.message}`); }
    } catch (error) { message(`Import could not be completed: ${error.message}`); }
    finally { await loadWatchlist(true); watchBusy = false; setWatchControls(); }
  }

  async function requestJson(url, signal, options = {}) {
    let response;
    try { response = await fetch(url, {...options, signal, headers: {Accept: "application/json", ...options.headers}, cache: "no-store"}); }
    catch (error) {
      if (error.name === "AbortError") throw error;
      throw new Error("The app could not be reached. Make sure it is still running and try again.");
    }
    let data;
    try { data = await response.json(); }
    catch { throw new Error("The service returned an unreadable response. Please try again."); }
    if (!response.ok) {
      const base = typeof data.error === "string" ? data.error.slice(0, 500) : "The request could not be completed.";
      const wait = Number(data.retryAfterSeconds);
      const error = new Error(Number.isFinite(wait) && wait > 0 ? `${base} Try again in about ${Math.ceil(wait)} seconds.` : base);
      error.status = response.status;
      throw error;
    }
    return data;
  }

  function cacheNote(target, data) {
    const parts = [];
    if (data.stale) parts.push("Showing older saved results because the price provider is temporarily unavailable.");
    else if (data.cached) parts.push("Showing recently cached results.");
    if (typeof data.warning === "string" && data.warning.trim()) parts.push(data.warning.trim().slice(0, 500));
    target.textContent = parts.join(" ");
    target.hidden = parts.length === 0;
  }

  function stateCard(title, description, error = false, retry = null) {
    const card = el("div", `empty-state${error ? " error-state" : ""}`);
    card.append(el("span", "empty-mark", error ? "!" : "⌕"), el("h3", "", title), el("p", "", description));
    if (retry) {
      const button = el("button", "retry-button", "Try again");
      button.type = "button";
      button.addEventListener("click", retry);
      card.append(button);
    }
    return card;
  }

  function loadingCard(text) {
    const card = el("div", "loading-state");
    const line = el("div", "loading-line");
    line.setAttribute("aria-hidden", "true");
    card.append(line, document.createTextNode(text));
    return card;
  }

  function renderResults() {
    ui.results.replaceChildren();
    if (!games.length) {
      ui.results.append(stateCard("No matching games this time.", "Try the full game title or check its spelling. Not every PC game or edition is listed."));
      return;
    }
    for (const game of games) {
      const button = el("button", "result-card");
      button.type = "button";
      button.dataset.gameId = game.id;
      button.setAttribute("aria-pressed", String(selectedGame?.id === game.id));
      button.setAttribute("aria-label", `Compare offers for ${game.title}, from ${priceText(game.cheapestPrice)}`);
      const monogram = el("span", "game-monogram", Array.from(game.title)[0].toUpperCase());
      monogram.setAttribute("aria-hidden", "true");
      const copy = el("span", "game-copy");
      copy.append(el("span", "game-title", game.title), el("span", "game-subtitle", "Compare store offers"));
      const price = el("span", "result-price");
      const value = el("span", "price-value", dollars(game.cheapestPrice));
      value.append(el("span", "price-currency", "USD"));
      price.append(el("span", "price-label", "FROM"), value);
      const arrow = el("span", "result-arrow", "↗");
      arrow.setAttribute("aria-hidden", "true");
      button.append(monogram, copy, price, arrow);
      button.addEventListener("click", () => openGame(game, button));
      ui.results.append(button);
    }
  }

  function markSelected() {
    ui.results.querySelectorAll("button[data-game-id]").forEach(button => {
      button.setAttribute("aria-pressed", String(button.dataset.gameId === selectedGame?.id));
    });
  }

  function closeDetail(restoreFocus = true) {
    detailController?.abort();
    detailVersion++;
    selectedGame = null;
    selectedDetail = null;
    ui.detail.hidden = true;
    ui.watchForm.hidden = true;
    ui.detail.removeAttribute("aria-busy");
    markSelected();
    if (restoreFocus && lastResultButton?.isConnected) lastResultButton.focus();
  }

  async function search(query = ui.query.value) {
    if (shuttingDown) return;
    query = query.trim();
    if (!query) { ui.query.focus(); return; }
    ui.query.value = query;
    searchController?.abort();
    searchController = new AbortController();
    const version = ++searchVersion;
    closeDetail(false);
    ui.resultsTitle.textContent = "Searching games";
    ui.searchStatus.textContent = `Looking for “${query}”…`;
    ui.resultsCount.hidden = true;
    ui.searchCache.hidden = true;
    ui.results.setAttribute("aria-busy", "true");
    ui.searchButton.textContent = "Searching…";
    ui.results.replaceChildren(loadingCard("Checking the game catalogue…"));
    try {
      const data = await requestJson(`/api/games/search?q=${encodeURIComponent(query)}`, searchController.signal);
      if (version !== searchVersion) return;
      if (!Array.isArray(data.games)) throw new Error("The service returned an unexpected game catalogue. Please try again.");
      games = data.games.map(normalizeGame).filter(Boolean);
      ui.resultsTitle.textContent = `Results for “${query}”`;
      ui.resultsCount.textContent = String(games.length);
      ui.resultsCount.hidden = false;
      const fetched = dateText(data.fetchedAt);
      const resolved = typeof data.resolvedQuery === "string" && data.resolvedQuery !== query
        ? ` Searched as “${data.resolvedQuery}”.` : "";
      ui.searchStatus.textContent = `${games.length} ${games.length === 1 ? "match" : "matches"} shown.${resolved} Select a game to compare offers.${fetched ? ` Data retrieved ${fetched}.` : ""}`;
      cacheNote(ui.searchCache, data);
      renderResults();
    } catch (error) {
      if (error.name === "AbortError" || version !== searchVersion) return;
      games = [];
      ui.resultsTitle.textContent = "Search unavailable";
      ui.searchStatus.textContent = "Your search could not be completed.";
      ui.results.replaceChildren(stateCard("Let's try that again.", error.message, true, () => search(query)));
    } finally {
      if (version === searchVersion) {
        ui.results.removeAttribute("aria-busy");
        ui.searchButton.replaceChildren(document.createTextNode("Find games "), el("span", "", "↗"));
        ui.searchButton.lastChild.setAttribute("aria-hidden", "true");
      }
    }
  }

  function normalizedOffers(values) {
    if (!Array.isArray(values)) return [];
    return values.filter(offer => offer && typeof offer.storeName === "string" && validPrice(offer.price))
      .map(offer => ({...offer, storeId: String(offer.storeId ?? ""), storeName: offer.storeName.slice(0, 200), active: offer.active === true}))
      .sort((left, right) => left.price - right.price || left.storeName.localeCompare(right.storeName));
  }

  async function openGame(game, trigger) {
    if (shuttingDown) return;
    detailController?.abort();
    detailController = new AbortController();
    const version = ++detailVersion;
    selectedGame = game;
    selectedDetail = null;
    lastResultButton = trigger;
    ui.detail.hidden = false;
    ui.detail.setAttribute("aria-busy", "true");
    ui.detailTitle.textContent = game.title;
    ui.detailStatus.textContent = "Loading current store offers…";
    ui.detailContent.hidden = true;
    ui.watchForm.hidden = true;
    ui.detailCache.hidden = true;
    ui.storeFilter.value = "all";
    ui.priceFilter.value = "";
    updateSaveButton();
    markSelected();
    ui.detail.focus({preventScroll: true});
    ui.detail.scrollIntoView({behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth", block: "start"});
    try {
      const data = await requestJson(`/api/games/game?id=${encodeURIComponent(game.id)}`, detailController.signal);
      if (version !== detailVersion) return;
      if (!Array.isArray(data.offers)) throw new Error("The service returned unexpected offer data. Please try again.");
      selectedDetail = {...data, offers: normalizedOffers(data.offers)};
      if (typeof data.title === "string" && data.title.trim()) {
        selectedGame = {...game, title: data.title.trim().slice(0, 1000)};
        ui.detailTitle.textContent = selectedGame.title;
      }
      const fetched = dateText(data.fetchedAt);
      ui.detailStatus.textContent = fetched ? `Price data retrieved ${fetched}. All prices in USD.` : "All prices in USD.";
      cacheNote(ui.detailCache, data);
      renderSummary();
      renderStoreFilter();
      renderOffers();
      ui.detailContent.hidden = false;
      updateSaveButton();
    } catch (error) {
      if (error.name === "AbortError" || version !== detailVersion) return;
      ui.detailStatus.textContent = error.message;
      ui.detailCache.replaceChildren(document.createTextNode("Offers are unavailable. "));
      const retry = el("button", "retry-button", "Try again");
      retry.type = "button";
      retry.addEventListener("click", () => openGame(game, trigger));
      ui.detailCache.append(retry);
      ui.detailCache.hidden = false;
    } finally {
      if (version === detailVersion) ui.detail.removeAttribute("aria-busy");
    }
  }

  function renderSummary() {
    const available = selectedDetail.offers.filter(offer => offer.active);
    const best = available[0];
    ui.bestPrice.textContent = best ? priceText(best.price) : "No offer";
    ui.bestStore.textContent = best ? `at ${best.storeName}` : "No active store offers are currently listed.";
    const low = selectedDetail.lowestEver;
    ui.lowestPrice.textContent = low && validPrice(low.price) ? priceText(low.price) : "Not recorded";
    const when = low ? dateText(low.at, false) : "";
    ui.lowestDate.textContent = when ? `Recorded ${when}. Future prices may differ.` : "Recorded by CheapShark; not a price guarantee.";
  }

  function renderStoreFilter() {
    ui.storeFilter.replaceChildren();
    const all = el("option", "", "All stores");
    all.value = "all";
    ui.storeFilter.append(all);
    const stores = new Map();
    selectedDetail.offers.forEach(offer => stores.set(offer.storeId, offer.storeName));
    [...stores].sort((left, right) => left[1].localeCompare(right[1])).forEach(([id, name]) => {
      const option = el("option", "", name);
      option.value = id;
      ui.storeFilter.append(option);
    });
  }

  function renderOffers() {
    if (!selectedDetail) return;
    const selectedStore = ui.storeFilter.value;
    const hasCap = ui.priceFilter.value.trim() !== "";
    const cap = Number(ui.priceFilter.value);
    const best = selectedDetail.offers.find(offer => offer.active);
    const offers = selectedDetail.offers.filter(offer => (selectedStore === "all" || offer.storeId === selectedStore)
      && (!hasCap || (Number.isFinite(cap) && cap >= 0 && offer.price <= cap)));
    ui.offers.replaceChildren();
    ui.offerCount.textContent = `${offers.length} ${offers.length === 1 ? "offer" : "offers"}`;
    ui.offersEmpty.hidden = offers.length > 0;
    ui.offersEmpty.textContent = selectedDetail.offers.length ? "No offers match your filters. Try another store or a higher price." : "No store offers are currently listed for this game.";
    for (const offer of offers) {
      const isBest = offer.active && best && offer.price === best.price;
      const row = el("tr", isBest ? "best-offer" : "");
      const store = el("td");
      store.append(el("span", "store-name", offer.storeName));
      if (isBest) store.append(el("span", "store-badge", "BEST PRICE"));
      if (!offer.active) store.append(el("span", "inactive-label", "Store currently inactive"));
      const price = el("td");
      price.append(el("span", "offer-price", dollars(offer.price)));
      if (validPrice(offer.retailPrice) && offer.retailPrice > offer.price) price.append(el("span", "regular-price", dollars(offer.retailPrice)));
      const saving = el("td");
      const savings = typeof offer.savings === "number" && Number.isFinite(offer.savings) ? Math.max(0, Math.min(100, offer.savings)) : 0;
      saving.append(el("span", "saving-value", savings > 0 ? `${Math.round(savings)}% off` : "—"));
      const action = el("td");
      const url = offer.active ? safeDealUrl(offer.dealUrl) : null;
      if (url) {
        const link = el("a", "offer-link");
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.setAttribute("aria-label", `View ${selectedGame.title} at ${offer.storeName} for ${priceText(offer.price)} (opens a new tab)`);
        link.append(document.createTextNode("View deal"), el("span", "", "↗"));
        link.lastChild.setAttribute("aria-hidden", "true");
        action.append(link);
      } else action.append(el("span", "unavailable-link", offer.active ? "Link unavailable" : "Unavailable"));
      row.append(store, price, saving, action);
      ui.offers.append(row);
    }
  }

  async function checkHealth() {
    if (shuttingDown) return;
    ui.healthButton.disabled = true;
    ui.healthLabel.textContent = "Checking…";
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch("/api/games/health", {signal: controller.signal, cache: "no-store"});
      let data;
      try { data = await response.json(); } catch { data = null; }
      const lastSuccess = data ? dateText(data.lastSuccessAt) : "";
      const lastError = data && typeof data.lastError === "string" ? data.lastError.trim().slice(0, 500) : "";
      const details = `${lastSuccess ? ` Last successful price lookup: ${lastSuccess}.` : ""}${lastError ? ` Last provider error: ${lastError}` : ""}`;
      if (data?.status === "ready") {
        ui.healthDot.className = "status-dot connected";
        ui.healthLabel.textContent = "App ready";
        message(`The local app is ready. No live price lookup has completed yet. Search for a game to check the provider.${details}`, true);
      } else if (data?.status === "healthy") {
        ui.healthDot.className = "status-dot connected";
        ui.healthLabel.textContent = "Provider healthy";
        message(`The local app is reachable, and its last observed price-provider lookup succeeded. This check does not make a new provider request.${details}`, true);
      } else if (data?.status === "degraded") {
        ui.healthDot.className = "status-dot failed";
        ui.healthLabel.textContent = "Provider degraded";
        message(`The local app is reachable, but its last price-provider lookup failed. Previously cached results may still be available.${details}`);
      } else {
        ui.healthDot.className = "status-dot failed";
        ui.healthLabel.textContent = "Health unconfirmed";
        message(`The local app responded (HTTP ${response.status}), but its price-provider health could not be confirmed.${details}`);
      }
    } catch {
      ui.healthDot.className = "status-dot failed";
      ui.healthLabel.textContent = "App unreachable";
      message("The local app did not respond to this health check. Make sure it is still running and try again.");
    } finally {
      clearTimeout(timeout);
      ui.healthButton.disabled = shuttingDown;
    }
  }

  ui.form.addEventListener("submit", event => { event.preventDefault(); search(); });
  document.querySelectorAll("button[data-search]").forEach(button => button.addEventListener("click", () => search(button.dataset.search)));
  byId("close-detail").addEventListener("click", () => closeDetail());
  ui.storeFilter.addEventListener("change", renderOffers);
  ui.priceFilter.addEventListener("input", renderOffers);
  ui.healthButton.addEventListener("click", checkHealth);
  ui.saveButton.addEventListener("click", () => {
    if (!selectedGame || !selectedDetail || !watchAvailable || watchBusy) return;
    const existing = saved.find(game => game.id === selectedGame.id);
    ui.targetPrice.value = existing?.targetPrice === null || !existing ? "" : existing.targetPrice.toFixed(2);
    ui.watchSave.textContent = existing ? "Update price watch" : "Save to watchlist";
    ui.watchForm.hidden = false;
    updateSaveButton(); ui.targetPrice.focus();
  });
  ui.watchForm.addEventListener("submit", event => {
    event.preventDefault();
    if (!selectedGame) return;
    try { saveWatchedGame(selectedGame, targetFromInput(ui.targetPrice)); }
    catch (error) { message(error.message); ui.targetPrice.focus(); }
  });
  ui.watchRefresh.addEventListener("click", refreshWatchPrices);
  ui.watchImportButton.addEventListener("click", importLegacySaved);
  ui.alertLink.addEventListener("click", () => byId("saved-title").focus());
  ui.quitButton.addEventListener("click", async () => {
    if (shuttingDown) return;
    ui.quitButton.disabled = true;
    try {
      const response = await fetch("/api/shutdown", {method: "POST", headers: {"X-App-Action": "change"}});
      if (!response.ok) throw new Error("Shutdown failed");
      shuttingDown = true;
      clearInterval(watchTimer);
      watchController?.abort();
      setWatchControls();
      ui.watchStatus.textContent = "App stopped. Price checks are paused.";
      ui.storageNote.textContent = "Your watchlist is saved on this computer. Reopen the app to resume price checks.";
      searchController?.abort();
      detailController?.abort();
      searchVersion++;
      detailVersion++;
      ui.searchButton.disabled = true;
      ui.query.disabled = true;
      ui.healthButton.disabled = true;
      ui.saveButton.disabled = true;
      ui.quitButton.textContent = "App stopped";
      ui.healthLabel.textContent = "Offline";
      ui.healthDot.className = "status-dot";
      message("The app has stopped. You can close this tab.", true);
    } catch {
      ui.quitButton.disabled = false;
      message("The app could not be stopped from this tab. It may already be closed.");
    }
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && !ui.detail.hidden) closeDetail();
    const target = event.target;
    if (event.key === "/" && !event.ctrlKey && !event.metaKey && !event.altKey
        && !(target instanceof HTMLInputElement) && !(target instanceof HTMLTextAreaElement) && !(target instanceof HTMLSelectElement) && !target.isContentEditable) {
      event.preventDefault(); ui.query.focus();
    }
  });
  readLegacySaved();
  renderSaved();
  loadWatchlist();
  watchTimer = setInterval(() => loadWatchlist(), 5000);
})();
