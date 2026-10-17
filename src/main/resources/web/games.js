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
    quitButton: byId("quit-button")
  };
  const SAVED_KEY = "game-price-search.saved.v1";
  const MAX_SAVED = 50;
  let saved = [];
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
      ? {month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"}
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

  function loadSaved() {
    saved = [];
    try {
      const text = localStorage.getItem(SAVED_KEY);
      if (!text) return;
      if (text.length > 100000) throw new Error("Saved list is too large");
      const parsed = JSON.parse(text);
      if (!Array.isArray(parsed)) throw new Error("Invalid saved list");
      const ids = new Set();
      saved = parsed.map(normalizeGame).filter(game => {
        if (!game || ids.has(game.id)) return false;
        ids.add(game.id);
        return true;
      }).slice(0, MAX_SAVED);
      if (saved.length !== parsed.length) message("Some saved entries could not be loaded. Your remaining games are still available.");
    } catch {
      message("Your saved list could not be read. You can still search and compare games; saving again will start a new list.");
    }
  }

  function persistSaved(next) {
    try {
      localStorage.setItem(SAVED_KEY, JSON.stringify(next.map(game => ({id: game.id, title: game.title, steamAppId: game.steamAppId}))));
      saved = next;
      renderSaved();
      updateSaveButton();
      return true;
    } catch {
      message("This browser could not save your shortlist. Check its storage settings or free some space, then try again.");
      return false;
    }
  }

  function renderSaved() {
    ui.savedList.replaceChildren();
    ui.savedCount.textContent = String(saved.length);
    ui.savedEmpty.hidden = saved.length > 0;
    saved.forEach((game, index) => {
      const row = el("li", "saved-item");
      const open = el("button", "saved-open");
      open.type = "button";
      open.append(el("span", "saved-number", String(index + 1).padStart(2, "0")), document.createTextNode(game.title));
      open.setAttribute("aria-label", `Check offers for ${game.title}`);
      open.addEventListener("click", () => openGame(game, open));
      const remove = el("button", "saved-remove", "×");
      remove.type = "button";
      remove.setAttribute("aria-label", `Remove ${game.title} from shortlist`);
      remove.addEventListener("click", () => {
        if (persistSaved(saved.filter(item => item.id !== game.id))) {
          message(`${game.title} removed from your shortlist.`, true);
          ui.savedList.querySelector("button")?.focus();
        }
      });
      row.append(open, remove);
      ui.savedList.append(row);
    });
  }

  function updateSaveButton() {
    const isSaved = selectedGame && saved.some(game => game.id === selectedGame.id);
    ui.saveButton.disabled = !selectedGame || !selectedDetail || shuttingDown;
    ui.saveButton.textContent = isSaved ? "✓ Saved game" : "＋ Save game";
    ui.saveButton.classList.toggle("is-saved", Boolean(isSaved));
    ui.saveButton.setAttribute("aria-pressed", String(Boolean(isSaved)));
  }

  async function requestJson(url, signal) {
    let response;
    try { response = await fetch(url, {signal, headers: {Accept: "application/json"}, cache: "no-store"}); }
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
      throw new Error(Number.isFinite(wait) && wait > 0 ? `${base} Try again in about ${Math.ceil(wait)} seconds.` : base);
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
    if (!selectedGame || !selectedDetail) return;
    const exists = saved.some(game => game.id === selectedGame.id);
    if (!exists && saved.length >= MAX_SAVED) { message(`Your shortlist holds up to ${MAX_SAVED} games. Remove one before saving another.`); return; }
    if (persistSaved(exists ? saved.filter(game => game.id !== selectedGame.id) : [...saved, selectedGame])) {
      message(exists ? `${selectedGame.title} removed from your shortlist.` : `${selectedGame.title} saved to your shortlist.`, true);
    }
  });
  ui.quitButton.addEventListener("click", async () => {
    if (shuttingDown) return;
    ui.quitButton.disabled = true;
    try {
      const response = await fetch("/api/shutdown", {method: "POST", headers: {"X-App-Action": "change"}});
      if (!response.ok) throw new Error("Shutdown failed");
      shuttingDown = true;
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
  window.addEventListener("storage", event => {
    if (event.key === SAVED_KEY || event.key === null) { loadSaved(); renderSaved(); updateSaveButton(); }
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && !ui.detail.hidden) closeDetail();
    const target = event.target;
    if (event.key === "/" && !event.ctrlKey && !event.metaKey && !event.altKey
        && !(target instanceof HTMLInputElement) && !(target instanceof HTMLTextAreaElement) && !(target instanceof HTMLSelectElement) && !target.isContentEditable) {
      event.preventDefault(); ui.query.focus();
    }
  });
  loadSaved();
  renderSaved();
})();
