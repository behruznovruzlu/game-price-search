(() => {
  "use strict";

  const byId = id => document.getElementById(id);
  const ui = {
    form: byId("deal-filters"), maxPrice: byId("max-price"), store: byId("deal-store"), sort: byId("deal-sort"),
    browse: byId("browse-button"), storeStatus: byId("store-status"), storeWarning: byId("store-warning"),
    storeWarningCopy: byId("store-warning-copy"), storeRetry: byId("store-retry"), discount: byId("min-discount"),
    grid: byId("deals-grid"), title: byId("deals-title"), count: byId("deals-count"), status: byId("deals-status"),
    cache: byId("deals-cache"), pagination: byId("deal-pagination"), previous: byId("previous-page"),
    next: byId("next-page"), pagePosition: byId("page-position"), pageNote: byId("page-note"),
    notice: byId("app-notice"), quit: byId("quit-button")
  };
  let snapshot = null;
  let appliedFilters = null;
  let dealsController = null;
  let storesController = null;
  let dealsVersion = 0;
  let storesLoading = false;
  let dealsLoading = false;
  let shuttingDown = false;

  function el(tag, className = "", text = "") {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }

  function validId(value) { return /^[1-9][0-9]{0,19}$/.test(String(value ?? "")); }
  function validPrice(value) { return typeof value === "number" && Number.isFinite(value) && value >= 0; }
  function dollars(value) { return `$${value.toLocaleString("en-US", {minimumFractionDigits: 2, maximumFractionDigits: 2})}`; }
  function dateText(value) {
    if (!value) return "";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "" : new Intl.DateTimeFormat(undefined,
      {year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"}).format(date);
  }

  function safeDealUrl(value) {
    try {
      const url = new URL(value);
      if (url.protocol !== "https:" || url.hostname !== "www.cheapshark.com" || url.pathname !== "/redirect"
          || url.username || url.password || url.port || [...url.searchParams.keys()].some(key => key !== "dealID")
          || url.searchParams.getAll("dealID").length !== 1 || !url.searchParams.get("dealID")
          || url.searchParams.get("dealID").length > 2048) return null;
      url.hash = "";
      return url.href;
    } catch { return null; }
  }

  function normalizeDeal(value) {
    if (!value || !validId(value.id) || !validId(value.storeId) || typeof value.title !== "string"
        || !value.title.trim() || typeof value.storeName !== "string" || !value.storeName.trim()
        || !validPrice(value.salePrice) || !validPrice(value.normalPrice)
        || value.normalPrice <= value.salePrice || !Number.isFinite(value.savings) || value.savings <= 0
        || value.savings > 100) return null;
    return {id: String(value.id), title: value.title.trim().slice(0, 1000), storeName: value.storeName.trim().slice(0, 200),
      salePrice: value.salePrice, normalPrice: value.normalPrice, savings: value.savings,
      dealRating: typeof value.dealRating === "number" && Number.isFinite(value.dealRating) && value.dealRating >= 0 && value.dealRating <= 10
        ? value.dealRating : null,
      dealUrl: safeDealUrl(value.dealUrl)};
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
      const base = typeof data?.error === "string" ? data.error.slice(0, 500) : "The request could not be completed.";
      const bodyWait = Number(data?.retryAfterSeconds);
      const headerWait = Number(response.headers.get("Retry-After"));
      const wait = Number.isFinite(bodyWait) && bodyWait > 0 ? bodyWait : headerWait;
      throw new Error(Number.isFinite(wait) && wait > 0 ? `${base} Try again in about ${Math.ceil(wait)} seconds.` : base);
    }
    return data;
  }

  function setControls() {
    ui.browse.disabled = shuttingDown || storesLoading || dealsLoading;
    ui.previous.disabled = shuttingDown || dealsLoading || !snapshot || snapshot.page === 0;
    ui.next.disabled = shuttingDown || dealsLoading || !snapshot?.hasMore || snapshot.page >= 1000;
    ui.storeRetry.disabled = shuttingDown || storesLoading;
    if (shuttingDown) ui.browse.textContent = "App stopped";
    else if (dealsLoading) ui.browse.textContent = "Finding deals…";
    else if (storesLoading) ui.browse.textContent = "Loading stores…";
    else {
      ui.browse.replaceChildren(document.createTextNode("Find deals "), el("span", "", "↗"));
      ui.browse.lastChild.setAttribute("aria-hidden", "true");
    }
  }

  function stateCard(title, description, retry = null) {
    const card = el("div", "empty-state");
    const mark = el("span", "empty-mark", retry ? "!" : "↗");
    mark.setAttribute("aria-hidden", "true");
    card.append(mark, el("h3", "", title), el("p", "", description));
    if (retry) {
      const button = el("button", "retry-button", "Try again");
      button.type = "button";
      button.addEventListener("click", retry);
      card.append(button);
    }
    return card;
  }

  function readFilters() {
    const maxPrice = ui.maxPrice.value.trim();
    if (maxPrice !== "" && (!/^\d+(?:\.\d{1,2})?$/.test(maxPrice) || Number(maxPrice) > 1000000)) {
      ui.maxPrice.setCustomValidity("Enter a price from 0 to 1,000,000 with no more than two decimal places, or leave it blank.");
      ui.maxPrice.reportValidity();
      return null;
    }
    ui.maxPrice.setCustomValidity("");
    if (!ui.form.reportValidity()) return null;
    return {maxPrice, storeId: ui.store.value, sort: ui.sort.value};
  }

  async function loadStores() {
    if (shuttingDown || storesLoading) return;
    storesLoading = true;
    setControls();
    const controller = new AbortController();
    storesController = controller;
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 20000);
    ui.storeStatus.textContent = "Loading stores…";
    try {
      const data = await requestJson("/api/stores", controller.signal);
      if (!Array.isArray(data?.stores)) throw new Error("The service returned an unexpected store list.");
      const stores = data.stores.filter(store => store && validId(store.id) && typeof store.name === "string" && store.name.trim());
      if (stores.length !== data.stores.length) throw new Error("The service returned an unexpected store list.");
      if (shuttingDown) return;
      const previous = ui.store.value;
      const all = el("option", "", "All participating stores");
      all.value = "";
      ui.store.replaceChildren(all);
      const unique = new Map(stores.map(store => [String(store.id), store.name.trim().slice(0, 200)]));
      [...unique].sort((a, b) => a[1].localeCompare(b[1])).forEach(([id, name]) => {
        const option = el("option", "", name);
        option.value = id;
        ui.store.append(option);
      });
      if (unique.has(previous)) ui.store.value = previous;
      ui.storeStatus.textContent = `${unique.size} participating ${unique.size === 1 ? "store" : "stores"}.`;
      const storeNote = typeof data.warning === "string" ? data.warning.trim().slice(0, 500) : "";
      const fetched = dateText(data.fetchedAt);
      ui.storeWarningCopy.textContent = data.stale
        ? `Showing an older saved store list.${fetched ? ` Retrieved ${fetched}.` : ""}${storeNote ? ` ${storeNote}` : ""}` : storeNote;
      ui.storeWarning.hidden = !ui.storeWarningCopy.textContent;
    } catch (error) {
      if (shuttingDown || error.name === "AbortError" && !timedOut) return;
      ui.storeStatus.textContent = "You can still browse all stores.";
      ui.storeWarningCopy.textContent = timedOut ? "The store list took too long to load. You can retry it separately." : error.message;
      ui.storeWarning.hidden = false;
    } finally {
      clearTimeout(timeout);
      storesLoading = false;
      storesController = null;
      setControls();
    }
  }

  function renderCache(data) {
    const parts = [];
    if (data.stale) parts.push("Showing older saved offers because the price provider is temporarily unavailable. Prices may have changed.");
    else if (data.cached) parts.push("Showing recently cached offers.");
    if (typeof data.warning === "string" && data.warning.trim()) parts.push(data.warning.trim().slice(0, 500));
    ui.cache.textContent = parts.join(" ");
    ui.cache.hidden = !parts.length;
  }

  function renderDeals() {
    if (!snapshot || dealsLoading || shuttingDown) return;
    const minimum = Number(ui.discount.value);
    const visible = snapshot.deals.filter(deal => deal.savings >= minimum);
    ui.grid.replaceChildren();
    ui.count.textContent = `${visible.length} / ${snapshot.deals.length}`;
    ui.count.setAttribute("aria-label", `${visible.length} of ${snapshot.deals.length} loaded store offers shown`);
    ui.count.hidden = false;
    const fetched = dateText(snapshot.fetchedAt);
    ui.status.textContent = `${visible.length} of ${snapshot.deals.length} loaded store offers shown on page ${snapshot.page + 1}.${fetched ? ` Price data retrieved ${fetched}.` : ""} All prices in USD.`;
    if (!snapshot.deals.length) {
      ui.grid.append(stateCard(snapshot.page > 0 ? "No offers on this page." : "No deals for these filters right now.",
        snapshot.page > 0 ? "Go back a page or change your budget, store or sorting. Listings can change as you browse."
          : "Try another store or a higher maximum price. Only currently discounted store offers are listed."));
    } else if (!visible.length) {
      const empty = stateCard("No offers match this page filter.", "Try a smaller discount or browse another page. This filter only narrows the offers already loaded here.");
      const clear = el("button", "retry-button", "Show every offer on this page");
      clear.type = "button";
      clear.addEventListener("click", () => { ui.discount.value = "0"; renderDeals(); });
      empty.append(clear);
      ui.grid.append(empty);
    }
    for (const deal of visible) {
      const card = el("article", "deal-card");
      const heading = el("div", "deal-card-heading");
      const monogram = el("span", "deal-monogram", Array.from(deal.title)[0].toUpperCase());
      monogram.setAttribute("aria-hidden", "true");
      heading.append(monogram, el("span", "deal-saving", `${Math.round(deal.savings)}% off`));
      const copy = el("div", "deal-card-copy");
      copy.append(el("p", "deal-store", deal.storeName), el("h3", "deal-title", deal.title));
      const prices = el("div", "deal-prices");
      const sale = el("span", "deal-sale", dollars(deal.salePrice));
      sale.append(el("span", "deal-currency", "USD"));
      sale.setAttribute("aria-label", `Sale price ${dollars(deal.salePrice)} US dollars`);
      const regular = el("s", "deal-regular", dollars(deal.normalPrice));
      regular.setAttribute("aria-label", `Regular price ${dollars(deal.normalPrice)} US dollars`);
      prices.append(sale, regular);
      copy.append(prices, el("p", "deal-rating", deal.dealRating === null ? "Current store offer" : `CheapShark deal rating ${deal.dealRating.toFixed(1)} / 10`));
      const actions = el("div", "deal-card-actions");
      if (deal.dealUrl) {
        const visit = el("a", "offer-link", "View deal");
        visit.href = deal.dealUrl;
        visit.target = "_blank";
        visit.rel = "noopener noreferrer";
        visit.setAttribute("aria-label", `View ${deal.title} at ${deal.storeName} for ${dollars(deal.salePrice)} USD (opens a new tab)`);
        const arrow = el("span", "", "↗");
        arrow.setAttribute("aria-hidden", "true");
        visit.append(arrow);
        actions.append(visit);
      } else actions.append(el("span", "unavailable-link", "Store link unavailable"));
      const compare = el("a", "compare-link", "Compare & watch");
      compare.href = `/?game=${encodeURIComponent(deal.id)}`;
      compare.setAttribute("aria-label", `Compare store offers and watch the price for ${deal.title}`);
      actions.append(compare);
      card.append(heading, copy, actions);
      ui.grid.append(card);
    }
    ui.pagination.hidden = !snapshot.deals.length && snapshot.page === 0 && !snapshot.hasMore;
    ui.pagePosition.textContent = `Page ${snapshot.page + 1}`;
    ui.pageNote.textContent = snapshot.page >= 1000 ? "Browsing limit reached. Refine your filters to find more offers."
      : snapshot.hasMore ? "Up to 30 offers per page. More may be available." : "No further page is indicated by the provider.";
    setControls();
  }

  async function loadDeals(filters, page = 0, moveFocus = false) {
    if (shuttingDown || !filters) return;
    dealsController?.abort();
    const controller = new AbortController();
    dealsController = controller;
    const version = ++dealsVersion;
    dealsLoading = true;
    snapshot = null;
    ui.pagination.hidden = true;
    ui.count.hidden = true;
    ui.cache.hidden = true;
    ui.grid.setAttribute("aria-busy", "true");
    const loading = el("div", "loading-state", "Checking current store offers…");
    const line = el("div", "loading-line");
    line.setAttribute("aria-hidden", "true");
    loading.prepend(line);
    ui.grid.replaceChildren(loading);
    ui.title.textContent = "Finding your next deal";
    ui.status.textContent = `Loading page ${page + 1}…`;
    setControls();
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 20000);
    try {
      const query = new URLSearchParams({sort: filters.sort, page: String(page)});
      if (filters.maxPrice !== "") query.set("maxPrice", filters.maxPrice);
      if (filters.storeId !== "") query.set("storeId", filters.storeId);
      const data = await requestJson(`/api/deals?${query}`, controller.signal);
      if (version !== dealsVersion || shuttingDown) return;
      if (!Array.isArray(data?.deals) || data.currency !== "USD" || data.page !== page || data.pageSize !== 30
          || data.deals.length > 30 || typeof data.hasMore !== "boolean") throw new Error("The service returned unexpected deal data. Please try again.");
      const deals = data.deals.map(normalizeDeal);
      if (deals.some(deal => !deal)) throw new Error("The service returned unexpected deal data. Please try again.");
      snapshot = {...data, deals};
      appliedFilters = {...filters};
      ui.title.textContent = "Current offers";
      renderCache(data);
    } catch (error) {
      if (shuttingDown || version !== dealsVersion || error.name === "AbortError" && !timedOut) return;
      ui.title.textContent = "Deals unavailable";
      ui.status.textContent = "This page of offers could not be loaded.";
      ui.grid.replaceChildren(stateCard("Let’s try that again.", timedOut
        ? "The request took too long to complete. Please try again." : error.message,
      () => loadDeals(filters, page)));
    } finally {
      clearTimeout(timeout);
      if (version === dealsVersion) {
        dealsLoading = false;
        dealsController = null;
        ui.grid.removeAttribute("aria-busy");
        renderDeals();
        setControls();
        if (moveFocus && !shuttingDown) {
          ui.title.setAttribute("tabindex", "-1");
          ui.title.focus({preventScroll: true});
          ui.title.scrollIntoView({behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth", block: "start"});
        }
      }
    }
  }

  ui.maxPrice.addEventListener("input", () => ui.maxPrice.setCustomValidity(""));
  ui.form.addEventListener("submit", event => {
    event.preventDefault();
    if (shuttingDown || storesLoading) return;
    const filters = readFilters();
    if (filters) loadDeals(filters);
  });
  ui.discount.addEventListener("change", renderDeals);
  ui.storeRetry.addEventListener("click", loadStores);
  ui.previous.addEventListener("click", () => {
    if (snapshot && !dealsLoading && snapshot.page > 0) loadDeals(appliedFilters, snapshot.page - 1, true);
  });
  ui.next.addEventListener("click", () => {
    if (snapshot?.hasMore && snapshot.page < 1000 && !dealsLoading) loadDeals(appliedFilters, snapshot.page + 1, true);
  });
  ui.quit.addEventListener("click", async () => {
    if (shuttingDown) return;
    ui.quit.disabled = true;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch("/api/shutdown", {method: "POST", headers: {"X-App-Action": "change"}, signal: controller.signal});
      if (!response.ok) throw new Error("Shutdown failed");
      shuttingDown = true;
      dealsVersion++;
      dealsController?.abort();
      storesController?.abort();
      ui.grid.removeAttribute("aria-busy");
      ui.maxPrice.disabled = true;
      ui.store.disabled = true;
      ui.sort.disabled = true;
      ui.discount.disabled = true;
      ui.quit.textContent = "App stopped";
      ui.notice.textContent = "The app has stopped. Your watchlist is saved on this computer. You can close this tab.";
      ui.notice.hidden = false;
      ui.status.textContent = "App stopped. Displayed offers are no longer being updated.";
      setControls();
    } catch {
      ui.quit.disabled = false;
      ui.notice.textContent = "The app could not be stopped from this tab. It may already be closed.";
      ui.notice.hidden = false;
    } finally { clearTimeout(timeout); }
  });
  (async () => {
    await loadStores();
    if (!shuttingDown) loadDeals(readFilters());
  })();
})();
