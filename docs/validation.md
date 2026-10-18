# Testing

## Automated tests

Requires JDK 21 and Maven. From the game repository root:

```sh
mvn clean verify
```

Tests use local fake providers and temporary data directories, so they do not depend on current CheapShark listings.

| Test class | Coverage |
| --- | --- |
| `GamePriceServiceTest` | Search results, store joins, price ordering, caches, rate limits and invalid provider responses. |
| `GameDealsTest` | Exact budgets, free offers, store filters, pagination and discovery-page caching. |
| `GameServerTest` | Routes, request validation, startup, shutdown and watchlist actions. |
| `WatchlistServiceTest` | Persistence, target alerts, acknowledgements, failed writes and concurrent refreshes. |
| `PriceWorkflowCheckTest` | Expected search results, valid offers, failed checks, expiry and scheduling. |

## Manual application check

Run with a separate data directory:

```sh
java -jar target/game-price-search-1.0.0.jar --open-browser --data-dir target/manual-check --workflow-check
```

- Search for **Portal**, open its offers and try the store and budget filters.
- Open **Discover deals**, change filters and move between pages.
- Save a price watch, request a check and acknowledge an alert if its target is reached.
- Use **Quit app**, restart with the same data directory and check that the saved watch remains.

## Monitor integration

Build both projects as sibling directories named `game-price-search` and `service-reliability-monitor`.

For a live process-outage check, run from the monitor repository root:

```sh
java scripts/VerifyGamePriceIntegration.java ../game-price-search/target/game-price-search-1.0.0.jar
```

This check makes real CheapShark lookups, stops its own game process, observes incidents and verifies recovery after restart. An internet connection is required, and store prices can change.

For deterministic workflow testing on Windows:

```sh
javac -cp "../game-price-search/target/game-price-search-1.0.0.jar;target/service-reliability-monitor-1.1.0.jar" -d target/workflow-verifier-classes scripts/VerifyWorkflowIntegration.java
java -cp "target/workflow-verifier-classes;../game-price-search/target/game-price-search-1.0.0.jar;target/service-reliability-monitor-1.1.0.jar" io.github.behruznovruzlu.games.VerifyWorkflowIntegration
```

Use `:` instead of `;` between classpath entries on Linux or macOS. This check uses a fake provider to exercise an incorrect game response, recovery and workflow expiry. Frequent monitor polls should not cause additional provider requests.

## Windows package

Build the portable package using the game repository's packaging script. From that repository root, verify the extracted package with:

```sh
python scripts/Verify-PortableWindows.py "path/to/GamePriceSearch"
```

The verifier checks file hashes, the actual CMD launcher, local assets, request protections, shutdown and restart using an isolated data directory.
