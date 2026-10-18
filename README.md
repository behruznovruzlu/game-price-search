# Game Price Search

A Java application for comparing PC game prices across stores listed by the CheapShark API. Search for a title, discover discounted games and track target prices in a persistent watchlist.

## Features

- Game search with store-price comparison and the lowest price recorded by CheapShark.
- Separate deal-discovery page with budget, store and sorting controls.
- Persistent watchlists with optional target prices and in-app alerts.
- Bounded caching, request pacing and cooldowns that respect `Retry-After`.
- Timestamps and warnings when older cached prices are displayed.
- Optional scheduled validation of a real search-and-offers workflow.

## Build and run

Requires **JDK 21** and **Maven 3.9+**.

```sh
mvn clean verify
java -jar target/game-price-search-1.0.0.jar --open-browser
```

The default address is `http://127.0.0.1:8090`. If occupied, the app chooses a free port. Use `--port 8090` to require a fixed port, or `--help` for all options. The server binds to IPv4 loopback for local use. New price lookups require internet access; no API key or Steam credentials are needed.

## Using the app

1. Search for a game and select a result to compare store offers.
2. Open **Discover deals** to browse discounts without entering a title.
3. Select **Watch price** to save a game with an optional USD target.
4. Use **Check prices** for an immediate check, or leave the app running for scheduled checks every 30 minutes.
5. Use **Quit app** to stop the server and scheduled work. Closing a browser tab leaves the app running.

Prices are supplied in **USD**. Discovery loads up to 30 store offers per page; the minimum-discount control narrows only that loaded page. Different editions and stores may appear separately. Confirm edition, regional availability and price on the store page before purchasing.

The watchlist is stored in `%LOCALAPPDATA%/GamePriceSearch` on Windows or `~/.game-price-search` elsewhere. Use `--data-dir path/to/data` to choose another directory. Only one running instance can use a given data directory. Checks and alerts run while the app is open; email and operating-system notifications are not implemented.

## Reliability and monitoring

| Endpoint | Purpose |
| --- | --- |
| `/api/games/readiness` | Local backend readiness. |
| `/api/games/health` | Last observed provider result, timestamps and cache counters. |
| `/api/games/workflow` | Latest validated search-and-offers result; HTTP 200 only for a recent passing check. |

Enable workflow validation with:

```sh
java -jar target/game-price-search-1.0.0.jar --workflow-check
```

This checks a fresh **Portal** search and its store offers on startup and every 15 minutes after completion. It bypasses cached price results and stale fallback while sharing the client's request limits. A passing result expires after 20 minutes. Disabled, unverified, failed and expired results return HTTP 503.

The companion **Service Reliability Monitor** checks these local endpoints. Its frequent probes do not trigger new CheapShark requests. Readiness and last-observed provider status are distinct from a validated workflow.

## Tests

`mvn clean verify` runs deterministic tests using local fake providers. Coverage includes response validation, active-store joins, exact price filters, caches, cooldowns, persistent watchlists, alert transitions and HTTP protections. Tests do not depend on current public game prices.

See [Architecture](docs/architecture.md) for request bounds, state handling and failure behaviour.

## Windows portable package

After building, run from PowerShell with a full JDK 21:

```powershell
.\scripts\Package-PortableWindows.ps1 -CreateZip
```

The script prints the generated package path under `dist/windows/`. Extract the complete package and open **GamePriceSearch.cmd**. It includes a Java runtime. The package is an unsigned development build; SHA256 checksums verify file integrity.

To verify an extracted package:

```sh
python scripts/Verify-PortableWindows.py "path/to/GamePriceSearch"
```

## Data source

Prices and store information come from [CheapShark](https://www.cheapshark.com/). Purchase links use its required redirect format. See the [CheapShark API documentation](https://apidocs.cheapshark.com/) for provider details.
