# Game Price Search

A local Java application for finding PC games and comparing store offers through the **CheapShark API**. Search a title, discover discounted games, compare prices and track target prices in a persistent watchlist.

## Use the application

Extract the complete Windows portable package, then double-click **`GamePriceSearch.cmd`**. The package includes a Java runtime and opens the interface in your browser; an installed JDK or terminal command is not needed.

1. Search for a game, such as **Portal**.
2. Select a result to see its current offers and lowest recorded price.
3. Filter by store or maximum price, then use **View deal** to open the purchase link.
4. Select **Watch price**, enter an optional target in USD and save the game. The watchlist holds up to 50 titles.
5. Use **Check prices** for an immediate check, or leave the application running for scheduled checks every 30 minutes. When a fresh observed price meets your target, an unread in-app alert appears. **Mark alert as read** acknowledges it.
6. Select **Quit app** when finished. Closing the browser tab alone leaves the local server running.

Prices are supplied in **USD**, without currency conversion. The lowest recorded price is historical data from CheapShark, not a prediction. Check the store's current price, regional availability and terms before purchasing.

### Discover deals

Select **Discover deals** to open the separate `/deals` page. Browse discounted store offers without entering a game title. Choose a maximum price in USD, an active store and an order: deal rating, discount, price or recent offers. Apply those choices to request one page, then use the previous/next controls to browse further. **Compare & watch** opens that game's offers on the search page; it does not add or change a price target automatically.

Each provider page contains up to 30 store offers, rather than 30 unique games. One game can appear at several stores. The application removes inactive stores and offers outside the exact selected budget while retaining the provider's order. The minimum-discount control filters only the current fetched page and does not query the full catalogue.

The next-page control is a continuation hint based on whether the provider returned a full page, not a total-result count or a guarantee of another matching offer. A page can be empty after filtering and still have a next page. Discovery runs on request; it does not download the catalogue or automatically follow purchase links. Cached or stale pages display their original observation time and any refresh warning.

The catalogue may list games under their full names and specific editions; it does not cover every PC game or edition. Common GTA V queries such as `GTA5`, `GTA 5` and `GTA V` are searched as `Grand Theft Auto V`, with the expanded title shown beside the results. Edition names returned by CheapShark are preserved.

New lookups require an internet connection. Searches run when requested; recently fetched results can be reused from memory. If a refresh fails, an available older response is shown with its original timestamp and a warning. The price cache is cleared when the app stops.

The watchlist, targets, last known prices and alert acknowledgements are saved on this computer, independently of the browser or port. The default Windows directory is `%LOCALAPPDATA%/GamePriceSearch`; other systems use `~/.game-price-search`. Use `--data-dir data` to select another directory. Only one running application can own a given data directory. Keep this personal state outside source control and distribution packages.

Checks and alerts run while the application is running. They are not an always-on service and do not send email or operating-system notifications. Reopening the app checks existing saved games. The UI shows the price observation timestamp separately from the latest attempted check. Failed or stale provider responses retain the last known price with an error and cannot raise a new alert. Acknowledged alerts do not repeat while the price remains below the same target; a later rise and drop can alert again.

If this browser has a shortlist from an older version, **Import saved games** moves it into the persistent watchlist. Existing targets are preserved and the old browser copy is kept until every import is confirmed. An old shortlist stored under another port must be imported from that browser address.

## Build and run

Development requirements: **JDK 21** and **Maven 3.9+**.

```sh
mvn clean verify
java -jar target/game-price-search-1.0.0.jar --open-browser
```

The default address is `http://127.0.0.1:8090`. If that port is occupied, the app chooses a free port unless a port was explicitly requested. Use `--port 8090` for a fixed address, or `--help` to list the options.

The server binds to IPv4 loopback and checks local Host/Origin headers. It is intended for one local user. The browser calls the Java backend, which makes read-only requests to CheapShark; no API key or Steam credentials are required.

## Local API and reliability

| Route | Purpose |
| --- | --- |
| `GET /api/games/search?q=Portal` | Find up to 30 matching titles. |
| `GET /api/games/game?id=82` | Fetch a game's offers, join store names and sort by price. |
| `GET /api/deals?maxPrice=10&storeId=1&sort=rating&page=0` | Browse one filtered, provider-ordered page of discounted offers. |
| `GET /api/stores` | Read the validated active-store choices, reusing the store metadata cache. |
| `GET /api/games/readiness` | Check local backend readiness without contacting CheapShark. |
| `GET /api/games/health` | Read the last observed provider result and cache/failure counters. |
| `GET /api/games/workflow` | Read the latest opt-in search-and-offers validation; HTTP 200 only for a recent passing check. |
| `POST /api/shutdown` | Stop the application; requires `X-App-Action: change`. |
| `GET /api/watchlist` | Read saved targets, observed prices, unread alerts and refresh state without querying the provider. |
| `POST /api/watchlist` | Save `{id,title,targetPrice}`; a null target saves without an alert. |
| `POST /api/watchlist/remove` | Remove `{id}` from the watchlist. |
| `POST /api/watchlist/acknowledge` | Mark the alert for `{id}` as read. |
| `POST /api/watchlist/refresh` | Queue one bounded asynchronous refresh; returns 202, or 409 if already checking/empty. |

Watchlist mutations require `Content-Type: application/json` and `X-App-Action: change`, reject unknown fields, and retain the same Host/Origin protections as shutdown. Saving a target alone does not query the provider. The UI explicitly requests a check after a user adds or edits a target; list polling is local. Price checks share the existing caches, request spacing and rate-limit cooldowns. The JSON state is size-limited and atomically replaced; write failures remain visible and do not discard the last persisted state.

The discovery route accepts an optional maximum USD price from 0 to 1,000,000 with at most two decimal places, an optional numeric store ID, `sort=rating|discount|price|recent` and a zero-based page from 0 to 1000. The minimum-discount display filter is not an API parameter. Deal pages share provider request spacing and cooldowns and have a separate bounded 15-minute cache keyed by the normalized price, store, order and page. No price target or watchlist state is included in a discovery request.

Provider health starts as `ready`: the app is reachable, but no successful lookup has been observed yet. A successful upstream lookup makes it `healthy`. An observed upstream failure makes it `degraded` with HTTP 503, even when older cached prices remain available. Cache reads do not erase an observed failure. A new successful upstream lookup restores provider health. Discovery and store-metadata lookups contribute to these provider observations; a successful store lookup alone does not establish that a game-search workflow passes.

These health observations include timestamps; they do not continuously prove that CheapShark is available. Both health routes are local snapshots and never initiate provider requests.

The companion **Service Reliability Monitor** can use its `config/game-price-search.properties` file to monitor readiness, last-observed provider health and the opt-in workflow report. These are three checks of one application. Its recurring probes do not generate game searches or follow purchase links; enable `--workflow-check` when using the workflow target.

The client uses bounded caches and response sizes, a six-second deadline per upstream request, serialized request spacing and cooldowns. HTTP 429 respects `Retry-After`. See [the architecture](docs/architecture.md) for the data flow and limits.

### Validate a real price-search workflow

Run with `--workflow-check` to enable an asynchronous check on startup and every 15 minutes:

```sh
java -jar target/game-price-search-1.0.0.jar --workflow-check
```

The check searches for **Portal**, requires the expected title, fetches that game's offers and verifies at least one active store offer with a valid USD price. Search and game-detail lookups bypass the application's price cache and stale fallback while sharing its request spacing, limits and provider cooldowns. Store metadata can use its existing cache. Purchase redirects are never followed. This validates the backend/provider workflow, not browser JavaScript, checkout or every catalogue title.

The local `/api/games/workflow` report includes stage durations, offer count, last completed check, last success, next scheduled check and validity deadline. Reading it never initiates an external request. Only a passing check less than 20 minutes old returns HTTP 200. Disabled, not-yet-verified, failed or expired checks return HTTP 503; a new failed check immediately invalidates an earlier pass. A busy local lookup defers validation for 30 seconds and does not itself count as a provider failure.

The combined game/monitor launcher enables validation and adds a third **Validated price workflow** target. A not-yet-completed initial check can temporarily show unavailable/Down while the backend readiness target passes; inspect the game page's **Live search validation** panel for its precise state. Standalone startup leaves this external check disabled unless explicitly requested. The ordinary one-second monitor probes still read local snapshots only.

## Windows package

From PowerShell with a full JDK 21 installed:

```powershell
.\scripts\Package-PortableWindows.ps1 -JavaHome "C:\Program Files\Java\jdk-21" -CreateZip
```

The script creates a fresh package under `dist/windows/` containing the application JAR, a reduced Java runtime, the CMD launcher, instructions and SHA256 checksums. It excludes source code, local caches and unrelated build output.

To exercise an extracted package through its actual launcher:

```powershell
python .\scripts\Verify-PortableWindows.py "path\to\extracted\GamePriceSearch"
```

Python is needed for this development check, not for running the app. The package is an unsigned development build; checksums verify file integrity and are not a trusted publisher signature.

## Verification

`mvn verify` runs deterministic tests with local fake providers for parsing, store joins, decimal ordering, caches, stale fallback, rate limiting, response bounds and HTTP boundaries. The test suite does not depend on the public API being available.

An explicit live integration check is available from the companion monitor repository after both projects have been built:

```sh
java scripts/VerifyGamePriceIntegration.java ../game-price-search/target/game-price-search-1.0.0.jar
```

That opt-in check makes real game lookups, observes the local health routes, stops only its own app process and verifies monitor incident/recovery behavior after restarting it. Purchase redirects are not probed.

- [Architecture and reliability decisions](docs/architecture.md)

## Data source and project origin

Prices and store data come from [CheapShark](https://www.cheapshark.com/). The app follows the [API documentation](https://apidocs.cheapshark.com/), including a descriptive User-Agent and the required CheapShark purchase redirect. It performs requested lookups rather than downloading the whole catalog.

The game API integration was first prototyped in a document-search repository. That early document-search experiment is now archived locally. The active applications are **Game Price Search** and its companion **Service Reliability Monitor**.

The backend uses Gson for JSON and JUnit for tests. No source license has been selected.
