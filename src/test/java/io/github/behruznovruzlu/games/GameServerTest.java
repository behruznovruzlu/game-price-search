package io.github.behruznovruzlu.games;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GameServerTest {
    @TempDir Path temporary;
    @Test void validatesRequestsBeforeCallingTheProviderAndKeepsHealthChecksLocal() throws Exception {
        try (var fixture = new Fixture(true)) {
            for (String path : List.of("/api/games/health", "/api/games/readiness"))
                assertEquals(200, fixture.get(path).statusCode());
            for (String query : List.of("", "q=", "q=portal&q=doom", "q=portal&unknown=x", "q=" + "x".repeat(121),
                    "q=" + "x".repeat(4096)))
                assertEquals(400, fixture.get("/api/games/search?" + query).statusCode(), query);
            for (String path : List.of("/api/games/game?id=../stores", "/api/games/game?id=1&id=2",
                    "/api/games/game?id=1&title=x", "/api/games/health?q=x", "/api/games/readiness?q=x", "/?q=x"))
                assertEquals(400, fixture.get(path).statusCode(), path);
            var crossOrigin = fixture.request("/api/games/search?q=portal").header("Origin", "https://unrelated.example").GET().build();
            assertEquals(403, fixture.send(crossOrigin).statusCode());
            var nullOrigin = fixture.request("/api/games/search?q=portal").header("Origin", "null").GET().build();
            assertEquals(403, fixture.send(nullOrigin).statusCode());
            var post = fixture.request("/api/games/search?q=portal").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, fixture.send(post).statusCode());
            assertEquals(0, fixture.calls.get(), "Invalid requests and monitoring must not contact CheapShark");

            var failure = fixture.get("/api/games/search?q=portal");
            assertEquals(502, failure.statusCode());
            assertTrue(JsonParser.parseString(failure.body()).getAsJsonObject().has("error"));
            assertEquals("5", failure.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(1, fixture.calls.get());
            var health = fixture.get("/api/games/health");
            assertEquals(503, health.statusCode());
            assertEquals("degraded", JsonParser.parseString(health.body()).getAsJsonObject().get("status").getAsString());
            assertEquals(200, fixture.get("/api/games/readiness").statusCode());
            assertEquals(1, fixture.calls.get(), "Health and readiness remain local after a provider failure");
        }
    }

    @Test void servesOnlyBundledGameResourcesAndRejectsTraversalAndOtherHostnames() throws Exception {
        try (var fixture = new Fixture(false)) {
            for (String path : List.of("/", "/games", "/games.js", "/games.css")) {
                var response = fixture.get(path);
                assertEquals(200, response.statusCode(), path);
                assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertFalse(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("https:"));
                assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
            assertTrue(fixture.get("/").body().contains("Game Price Search"));
            for (String path : List.of("/documents", "/api/documents", "/api/search?q=test", "/../pom.xml",
                    "/%2e%2e/pom.xml", "/api/games/%73earch?q=portal", "/web/games.html"))
                assertEquals(404, fixture.get(path).statusCode(), path);
            assertEquals(0, fixture.calls.get());
            try (Socket socket = new Socket("127.0.0.1", fixture.server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("GET /api/games/readiness HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 403"), response);
            }
        }
    }

    @Test void returnsSearchAndJoinedOffersAndServesCachedResultsWithoutUpstreamPolling() throws Exception {
        try (var fixture = new Fixture(false)) {
            var sameOrigin = fixture.request("/api/games/search?q=Example%20%26%20Game")
                    .header("Origin", fixture.base).GET().build();
            var response = fixture.send(sameOrigin);
            assertEquals(200, response.statusCode());
            var results = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("USD", results.get("currency").getAsString());
            assertEquals("Example & Game", results.getAsJsonArray("games").get(0).getAsJsonObject().get("title").getAsString());
            var detail = fixture.get("/api/games/game?id=123");
            assertEquals(200, detail.statusCode());
            var offers = JsonParser.parseString(detail.body()).getAsJsonObject().getAsJsonArray("offers");
            assertEquals("Steam", offers.get(0).getAsJsonObject().get("storeName").getAsString());
            assertTrue(offers.get(0).getAsJsonObject().get("dealUrl").getAsString().startsWith("https://www.cheapshark.com/redirect?dealID="));
            assertEquals(3, fixture.calls.get());
            assertTrue(JsonParser.parseString(fixture.get("/api/games/search?q=Example%20%26%20Game").body())
                    .getAsJsonObject().get("cached").getAsBoolean());
            assertEquals("healthy", JsonParser.parseString(fixture.get("/api/games/health").body()).getAsJsonObject().get("status").getAsString());
            assertEquals(3, fixture.calls.get());
        }
    }

    @Test void shutdownRequiresAnExplicitSameOriginActionAndReleasesResources() throws Exception {
        try (var fixture = new Fixture(false)) {
            CountDownLatch stopped = new CountDownLatch(1);
            fixture.server.onShutdown(stopped::countDown);
            assertEquals(404, fixture.get("/api/shutdown").statusCode());
            var missingHeader = fixture.request("/api/shutdown").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, fixture.send(missingHeader).statusCode());
            var wrongOrigin = fixture.request("/api/shutdown").header("X-App-Action", "change")
                    .header("Origin", "https://unrelated.example").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, fixture.send(wrongOrigin).statusCode());
            var query = fixture.request("/api/shutdown?extra=1").header("X-App-Action", "change")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(400, fixture.send(query).statusCode());
            assertEquals(1, stopped.getCount());
            var shutdown = fixture.request("/api/shutdown").header("X-App-Action", "change")
                    .header("Origin", fixture.base).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(200, fixture.send(shutdown).statusCode());
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            fixture.server.close();
            fixture.server.close();
            var closed = assertThrows(GamePriceService.ServiceException.class, () -> fixture.service.search("Example"));
            assertEquals(503, closed.status());
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test void failedBindClosesTheOwnedClientAndImplicitPortSelectionFallsBack() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = occupied.getLocalPort();
            var service = new GamePriceService(URI.create("http://127.0.0.1:1/"), Clock.systemUTC(), Duration.ofMinutes(15), Duration.ZERO);
            assertThrows(BindException.class, () -> new GameServer(port, service));
            assertEquals(503, assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example")).status());
            assertThrows(BindException.class, () -> Main.createServer(new Main.Options(port, true, false, false, temporary, false)));
            try (var fallback = Main.createServer(new Main.Options(port, false, false, false, temporary, false))) {
                assertNotEquals(port, fallback.port());
                assertTrue(fallback.port() > 0);
            }
        }
    }

    @Test void acceptsOnlyGameApplicationStartupOptions() {
        assertEquals(new Main.Options(8090, false, false, false, Main.defaultDataDirectory(), false), Main.parseOptions(new String[0]));
        assertEquals(new Main.Options(0, true, true, false, Main.defaultDataDirectory(), false), Main.parseOptions(new String[]{"--open-browser", "--port", "0"}));
        assertTrue(Main.parseOptions(new String[]{"--workflow-check"}).workflowCheck());
        assertEquals(temporary, Main.parseOptions(new String[]{"--data-dir", temporary.toString()}).dataDir());
        assertTrue(Main.parseOptions(new String[]{"--help"}).help());
        for (String[] options : List.of(new String[]{"--data-dir"}, new String[]{"--data-dir", " "}, new String[]{"--docs", "anything"},
                new String[]{"--port"}, new String[]{"--port", "70000"}, new String[]{"--port", "wrong"},
                new String[]{"--data-dir", "one", "--data-dir", "two"},
                new String[]{"--workflow-check", "--workflow-check"},
                new String[]{"--port", "0", "--port", "0"}, new String[]{"--open-browser", "--open-browser"}))
            assertThrows(IllegalArgumentException.class, () -> Main.parseOptions(options));
    }

    @Test void persistsWatchTargetsAcrossPortsAndProtectsEveryMutation() throws Exception {
        String item = "{\"id\":\"123\",\"title\":\"Example & Game\",\"targetPrice\":10.00}";
        try (var fixture = new Fixture(false, temporary)) {
            assertEquals(0, JsonParser.parseString(fixture.get("/api/watchlist").body()).getAsJsonObject().getAsJsonArray("items").size());
            var unmarked = fixture.request("/api/watchlist").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(item)).build();
            assertEquals(403, fixture.send(unmarked).statusCode());
            var foreign = fixture.request("/api/watchlist").header("Content-Type", "application/json")
                    .header("X-App-Action", "change").header("Origin", "https://unrelated.example")
                    .POST(HttpRequest.BodyPublishers.ofString(item)).build();
            assertEquals(403, fixture.send(foreign).statusCode());
            for (String invalid : List.of("[]", "{}", item + " trailing", item.replace("10.00", "-1"),
                    item.replace("10.00", "\"10.00\""), item.replace("\"123\"", "\"0\""),
                    item.replace("\"title\"", "\"unknown\""), " ".repeat(8193)))
                assertEquals(400, fixture.post("/api/watchlist", invalid).statusCode(), invalid.substring(0, Math.min(80, invalid.length())));
            assertEquals(200, fixture.post("/api/watchlist", item).statusCode());
            assertEquals(0, fixture.calls.get(), "Saving a target does not spend the price provider's rate limit.");
            assertEquals(202, fixture.post("/api/watchlist/refresh", "{}").statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            com.google.gson.JsonObject watched = null;
            while (System.nanoTime() < deadline) {
                watched = JsonParser.parseString(fixture.get("/api/watchlist").body()).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();
                if (watched.get("targetReached").getAsBoolean()) break;
                Thread.sleep(20);
            }
            assertNotNull(watched);
            assertEquals("9.99", watched.get("lastPrice").getAsString());
            assertTrue(watched.get("targetReached").getAsBoolean());
            assertTrue(watched.get("notificationUnread").getAsBoolean());
            assertEquals(200, fixture.post("/api/watchlist/acknowledge", "{\"id\":\"123\"}").statusCode());
            assertEquals(400, fixture.post("/api/watchlist/refresh", "{\"extra\":true}").statusCode());
        }
        try (var restarted = new Fixture(false, temporary)) {
            var watched = JsonParser.parseString(restarted.get("/api/watchlist").body()).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();
            assertEquals("123", watched.get("id").getAsString());
            assertEquals(0, new java.math.BigDecimal("10.00").compareTo(watched.get("targetPrice").getAsBigDecimal()));
            assertFalse(watched.get("notificationUnread").getAsBoolean(), "Acknowledgements survive a restart at a different local URL.");
            assertEquals(200, restarted.post("/api/watchlist/remove", "{\"id\":\"123\"}").statusCode());
            assertEquals(0, JsonParser.parseString(restarted.get("/api/watchlist").body()).getAsJsonObject().getAsJsonArray("items").size());
        }
    }

    @Test void exposesValidatedWorkflowStateWithoutRunningChecksOnEveryRead() throws Exception {
        try (var disabled = new Fixture(false)) {
            var response = disabled.get("/api/games/workflow");
            assertEquals(503, response.statusCode());
            assertEquals("disabled", JsonParser.parseString(response.body()).getAsJsonObject().get("status").getAsString());
            assertEquals(0, disabled.calls.get());
        }
        for (boolean correctContents : List.of(false, true)) {
            try (var fixture = new Fixture(false, null, true, correctContents)) {
                String wanted = correctContents ? "healthy" : "failed";
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                HttpResponse<String> report = null;
                while (System.nanoTime() < deadline) {
                    report = fixture.get("/api/games/workflow");
                    var snapshot = JsonParser.parseString(report.body()).getAsJsonObject();
                    if (!snapshot.get("running").getAsBoolean() && snapshot.get("status").getAsString().equals(wanted)) break;
                    Thread.sleep(20);
                }
                assertNotNull(report);
                assertEquals(wanted, JsonParser.parseString(report.body()).getAsJsonObject().get("status").getAsString());
                assertEquals(correctContents ? 200 : 503, report.statusCode());
                assertEquals(200, fixture.get("/api/games/readiness").statusCode(), "An invalid search response is distinct from an unreachable backend.");
                int observedCalls = fixture.calls.get();
                for (int index = 0; index < 5; index++) fixture.get("/api/games/workflow");
                assertEquals(400, fixture.get("/api/games/workflow?force=1").statusCode());
                assertEquals(observedCalls, fixture.calls.get(), "Monitoring polls must only read the completed report.");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer upstream;
        final GamePriceService service;
        final GameServer server;
        final HttpClient client = HttpClient.newHttpClient();
        final String base;

        Fixture(boolean failure) throws Exception {
            this(failure, null);
        }

        Fixture(boolean failure, Path dataDir) throws Exception {
            this(failure, dataDir, false, false);
        }

        Fixture(boolean failure, Path dataDir, boolean workflowEnabled, boolean correctWorkflowContents) throws Exception {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/", exchange -> {
                calls.incrementAndGet();
                String body;
                if (failure) body = "{\"error\":\"temporarily unavailable\"}";
                else if (exchange.getRequestURI().getPath().endsWith("stores"))
                    body = "[{\"storeID\":\"1\",\"storeName\":\"Steam\",\"isActive\":1}]";
                else if (exchange.getRequestURI().getQuery().startsWith("id="))
                    body = "{\"info\":{\"title\":\"Example & Game\",\"steamAppID\":\"570\"},\"cheapestPriceEver\":{\"price\":\"4.99\",\"date\":1704067200},\"deals\":[{\"storeID\":\"1\",\"dealID\":\"offer\",\"price\":\"9.99\",\"retailPrice\":\"19.99\",\"savings\":\"50\"}]}";
                else body = "[{\"gameID\":\"123\",\"steamAppID\":\"570\",\"external\":\"Example & Game\",\"cheapest\":\"9.99\",\"cheapestDealID\":\"offer\"}]";
                if (correctWorkflowContents) body = body.replace("Example & Game", "Portal");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try (exchange) {
                    exchange.sendResponseHeaders(failure ? 503 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            upstream.start();
            service = new GamePriceService(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/"),
                    Clock.systemUTC(), Duration.ofMinutes(15), Duration.ZERO);
            server = new GameServer(0, service, dataDir == null ? null : new WatchlistService(dataDir, service),
                    workflowEnabled ? new PriceWorkflowCheck(service) : null);
            server.start();
            base = "http://127.0.0.1:" + server.port();
        }

        HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)); }
        HttpResponse<String> get(String path) throws Exception { return send(request(path).GET().build()); }
        HttpResponse<String> post(String path, String body) throws Exception {
            return send(request(path).header("Content-Type", "application/json").header("X-App-Action", "change")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }
        HttpResponse<String> send(HttpRequest request) throws Exception {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        @Override public void close() { server.close(); client.close(); upstream.stop(0); }
    }
}
