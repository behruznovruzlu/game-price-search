package io.github.behruznovruzlu.games;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class GamePriceServiceTest {
    private static final String SEARCH = """
            [{"gameID":"123","steamAppID":"570","external":"Example & Game","cheapest":"9.99","cheapestDealID":"abc%2Fdef%2Bghi%3D"}]
            """;
    private static final String STORES = """
            [{"storeID":"1","storeName":"Steam","isActive":1},
             {"storeID":"2","storeName":"GOG","isActive":1},
             {"storeID":"3","storeName":"Inactive store","isActive":0}]
            """;
    private static final String GAME = """
            {"info":{"title":"Example & Game","steamAppID":"570"},
             "cheapestPriceEver":{"price":"4.99","date":1704067200},
             "deals":[
               {"storeID":"1","dealID":"steam%2Foffer","price":"9.99","retailPrice":"19.99","savings":"50.025013"},
               {"storeID":"3","dealID":"inactive","price":"1.00","retailPrice":"19.99","savings":"94.99"},
               {"storeID":"2","dealID":"gog+offer=","price":"7.49","retailPrice":"19.99","savings":"62.53"}]}
            """;
    private HttpServer upstream;
    private GamePriceService service;
    private MutableClock clock;
    private AtomicReference<Function<HttpExchange, Reply>> responder;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<URI> paths = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-17T12:00:00Z"));
        responder = new AtomicReference<>(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("stores")) return new Reply(200, STORES);
            return new Reply(200, exchange.getRequestURI().getQuery().startsWith("id=") ? GAME : SEARCH);
        });
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI());
            try {
                Reply reply = responder.get().apply(exchange);
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        upstream.start();
        service = new GamePriceService(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/1.0/"),
                clock, Duration.ofMinutes(15), Duration.ZERO);
    }

    @AfterEach void stop() {
        if (service != null) service.close();
        if (upstream != null) upstream.stop(0);
    }

    @Test void searchesSafelyAndCachesWithoutLosingTheOriginalFetchTime() {
        JsonObject ready = json(service.health());
        assertEquals("ready", ready.get("status").getAsString());
        assertTrue(ready.get("lastSuccessAt").isJsonNull());
        assertEquals(0, requests.get());
        JsonObject result = json(service.search("  Example & Game  "));
        assertEquals("USD", result.get("currency").getAsString());
        assertEquals("Example & Game", result.get("query").getAsString());
        assertFalse(result.get("cached").getAsBoolean());
        assertFalse(result.get("stale").getAsBoolean());
        JsonObject game = result.getAsJsonArray("games").get(0).getAsJsonObject();
        assertEquals("https://www.cheapshark.com/redirect?dealID=abc%2Fdef%2Bghi%3D", game.get("dealUrl").getAsString());
        assertEquals("title=Example%20%26%20Game&limit=30&exact=0", paths.getFirst().getRawQuery());
        clock.advance(Duration.ofMinutes(2));
        JsonObject cached = json(service.search("Example & Game"));
        assertTrue(cached.get("cached").getAsBoolean());
        assertEquals(result.get("fetchedAt"), cached.get("fetchedAt"));
        assertEquals(1, requests.get());
        assertEquals("healthy", json(service.health()).get("status").getAsString());
    }

    @Test void joinsStoreMetadataOmitsInactiveOffersAndSortsPrices() {
        JsonObject game = json(service.game("123"));
        assertEquals(2, requests.get());
        var offers = game.getAsJsonArray("offers");
        assertEquals(2, offers.size());
        assertEquals("GOG", offers.get(0).getAsJsonObject().get("storeName").getAsString());
        assertEquals("7.49", offers.get(0).getAsJsonObject().get("price").getAsString());
        assertEquals("https://www.cheapshark.com/redirect?dealID=gog%2Boffer%3D", offers.get(0).getAsJsonObject().get("dealUrl").getAsString());
        assertEquals("2024-01-01T00:00:00Z", game.getAsJsonObject("lowestEver").get("at").getAsString());
        assertEquals("Steam", offers.get(1).getAsJsonObject().get("storeName").getAsString());
        service.game("124");
        assertEquals(3, requests.get(), "Store metadata is cached across game requests.");
    }

    @Test void preservesStalePricesAndTimestampOnUpstreamFailure() {
        JsonObject original = json(service.search("Example"));
        clock.advance(Duration.ofMinutes(16));
        responder.set(exchange -> new Reply(503, "unavailable"));
        JsonObject stale = json(service.search("Example"));
        assertTrue(stale.get("cached").getAsBoolean());
        assertTrue(stale.get("stale").getAsBoolean());
        assertFalse(stale.get("warning").getAsString().isBlank());
        assertEquals(original.get("fetchedAt"), stale.get("fetchedAt"));
        assertEquals(1, stale.getAsJsonArray("games").size());
        assertEquals("degraded", json(service.health()).get("status").getAsString());
        service.search("Example");
        assertEquals(2, requests.get(), "Cooldown serves the stale cache without another upstream request.");
        assertEquals(503, assertThrows(GamePriceService.ServiceException.class, () -> service.search("Another")).status());
        clock.advance(Duration.ofSeconds(6));
        responder.set(exchange -> new Reply(200, SEARCH));
        assertFalse(json(service.search("Example")).get("stale").getAsBoolean());
        assertEquals("healthy", json(service.health()).get("status").getAsString());
    }

    @Test void respectsRetryAfterAndDoesNotStormTheUpstream() {
        responder.set(exchange -> new Reply(429, "rate limited", Map.of("Retry-After", "17")));
        var limited = assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example"));
        assertEquals(503, limited.status());
        assertEquals(17, limited.retryAfterSeconds());
        for (int attempt = 0; attempt < 5; attempt++)
            assertThrows(GamePriceService.ServiceException.class, () -> service.search("Other"));
        assertEquals(1, requests.get());
        clock.advance(Duration.ofSeconds(18));
        responder.set(exchange -> new Reply(200, SEARCH));
        service.search("Other");
        assertEquals(2, requests.get());
    }

    @Test void doesNotShortenLongOrOverflowingRetryAfterValues() {
        responder.set(exchange -> new Reply(429, "rate limited", Map.of("Retry-After", "172800")));
        assertEquals(172800, assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example")).retryAfterSeconds());
        clock.advance(Duration.ofDays(3));
        responder.set(exchange -> new Reply(429, "rate limited", Map.of("Retry-After", "99999999999999999999999999999999")));
        assertTrue(assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example")).retryAfterSeconds() > 172800);
    }

    @Test void rejectsMalformedDataAndUntrustedDealLinksInsteadOfReturningEmptySuccess() {
        for (String invalid : List.of("{}", "[{broken]", SEARCH.replace("9.99", "-1.00"),
                SEARCH.replace("abc%2Fdef%2Bghi%3D", "abc%26other%3Devil"),
                SEARCH.replace("abc%2Fdef%2Bghi%3D", "abc%252Fdef"), SEARCH + " trailing")) {
            responder.set(exchange -> new Reply(200, invalid));
            assertEquals(502, assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example")).status(), invalid);
            assertEquals("degraded", json(service.health()).get("status").getAsString());
            clock.advance(Duration.ofSeconds(6));
        }
        responder.set(exchange -> new Reply(200, "[]"));
        assertEquals(0, json(service.search("Example")).getAsJsonArray("games").size());
    }

    @Test void validatesInputBeforeContactingTheNetwork() {
        for (String query : List.of("", " ", "x".repeat(121), "a\u0000b"))
            assertEquals(400, assertThrows(GamePriceService.ServiceException.class, () -> service.search(query)).status());
        for (String id : List.of("", "0", "-1", "1&title=other", "https://other.example"))
            assertEquals(400, assertThrows(GamePriceService.ServiceException.class, () -> service.game(id)).status());
        assertEquals(0, requests.get());
        assertEquals("ready", json(service.health()).get("status").getAsString());
    }

    @Test void selectsNativeWindowsRootsOnlyWhenNoExplicitTrustSettingsExist() {
        URI https = URI.create("https://www.cheapshark.com/api/1.0/");
        URI http = URI.create("http://127.0.0.1/");
        assertTrue(GamePriceService.usesWindowsTrust(https, "Windows 11", null, null));
        assertFalse(GamePriceService.usesWindowsTrust(http, "Windows 11", null, null));
        assertFalse(GamePriceService.usesWindowsTrust(https, "Linux", null, null));
        assertFalse(GamePriceService.usesWindowsTrust(https, "Windows 11", "custom-trust-store", null));
        assertFalse(GamePriceService.usesWindowsTrust(https, "Windows 11", "", null));
        assertFalse(GamePriceService.usesWindowsTrust(https, "Windows 11", null, "JKS"));
    }

    @Test void refusesRedirectsAndOversizedResponses() {
        responder.set(exchange -> new Reply(302, "redirect", Map.of("Location", "http://127.0.0.1:1/not-allowed")));
        assertEquals(502, assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example")).status());
        assertEquals(1, requests.get());
        clock.advance(Duration.ofSeconds(6));
        responder.set(exchange -> new Reply(200, "[" + " ".repeat(1024 * 1024) + "]"));
        assertEquals(502, assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example")).status());
    }

    @Test void concurrentLookupsFailFastWithoutClaimingAnUpstreamFailureAndHealthNeverWaits() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        responder.set(exchange -> {
            arrived.countDown();
            try { if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Test request was not released."); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new Reply(200, SEARCH);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.search("Example"));
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(GamePriceService.ServiceException.class,
                    () -> service.search("Example")));
            var localHealth = executor.submit(() -> service.health());
            try {
                assertEquals("ready", json(localHealth.get(500, TimeUnit.MILLISECONDS)).get("status").getAsString());
                var busy = second.get(1, TimeUnit.SECONDS);
                assertEquals(503, busy.status());
                assertEquals(1, busy.retryAfterSeconds());
                assertEquals(0, json(service.health()).get("upstreamFailures").getAsLong());
                assertTrue(json(service.health()).get("lastFailureAt").isJsonNull());
            }
            finally { release.countDown(); }
            assertFalse(json(first.get(3, TimeUnit.SECONDS)).get("cached").getAsBoolean());
            assertTrue(json(service.search("Example")).get("cached").getAsBoolean());
        }
        assertEquals(1, requests.get());
    }

    @Test void enforcesDeadlineForAResponseThatSendsHeadersButNeverFinishesItsBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        upstream.createContext("/api/1.0/games", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('[');
                exchange.getResponseBody().flush();
                release.await(9, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try {
            var error = assertThrows(GamePriceService.ServiceException.class, () -> service.search("Example"));
            assertEquals(504, error.status());
            assertEquals("degraded", json(service.health()).get("status").getAsString());
        } finally { release.countDown(); }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private record Reply(int status, String body, Map<String, String> headers) {
        Reply(int status, String body) { this(status, body, Map.of()); }
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
