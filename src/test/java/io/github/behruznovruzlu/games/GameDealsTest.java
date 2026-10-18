package io.github.behruznovruzlu.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class GameDealsTest {
    private static final String STORES = """
            [{"storeID":"1","storeName":"Steam","isActive":1},
             {"storeID":"2","storeName":"GOG","isActive":1},
             {"storeID":"3","storeName":"Inactive","isActive":0}]
            """;
    private HttpServer upstream;
    private GamePriceService service;
    private MutableClock clock;
    private final List<URI> paths = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<HttpExchange, Reply>> responder = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
        responder.set(exchange -> new Reply(200, "[" + deal("123", "1", "9.99", "19.99") + "]"));
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI());
            try {
                assertEquals("GamePriceSearch/1.0 (local desktop application)", exchange.getRequestHeaders().getFirst("User-Agent"));
                Reply reply = exchange.getRequestURI().getPath().endsWith("stores")
                        ? new Reply(200, STORES) : responder.get().apply(exchange);
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

    @Test void discoversOnlyActiveDiscountedOffersAndPreservesEditionTitlesAndSafeLinks() {
        responder.set(exchange -> new Reply(200, "[" + String.join(",",
                deal("123", "1", "9.99", "19.99"), deal("124", "3", "1.00", "19.99"),
                deal("125", "2", "20.00", "20.00"),
                deal("127", "2", "3.00", "20.00").replace("\"savings\":\"50.025013\"", "\"savings\":\"0\""),
                deal("126", "2", "3.00", "20.00").replace("\"isOnSale\":\"1\"", "\"isOnSale\":\"0\"")) + "]"));
        JsonObject result = json(service.deals(null, null, "rating", 0));
        assertEquals("USD", result.get("currency").getAsString());
        assertFalse(result.get("cached").getAsBoolean());
        assertFalse(result.get("stale").getAsBoolean());
        assertFalse(result.get("hasMore").getAsBoolean());
        JsonArray deals = result.getAsJsonArray("deals");
        assertEquals(1, deals.size());
        JsonObject item = deals.get(0).getAsJsonObject();
        assertEquals("123", item.get("id").getAsString());
        assertEquals("Example Game - Deluxe Edition", item.get("title").getAsString());
        assertEquals("Steam", item.get("storeName").getAsString());
        assertEquals("https://www.cheapshark.com/redirect?dealID=offer%2F123%2Babc%3D", item.get("dealUrl").getAsString());
        assertEquals(2, paths.size());
        assertEquals("healthy", json(service.health()).get("status").getAsString());
    }

    @Test void enforcesExactBudgetEvenForTheProvidersFiftyDollarUnlimitedSentinel() {
        responder.set(exchange -> new Reply(200, "[" + String.join(",",
                deal("123", "1", "50.00", "100.00"), deal("124", "1", "50.01", "100.00"),
                deal("125", "2", "49.99", "100.00")) + "]"));
        JsonObject fifty = json(service.deals(new BigDecimal("50.00"), "all", "price", 0));
        assertEquals(2, fifty.getAsJsonArray("deals").size());
        assertFalse(paths.getLast().getRawQuery().contains("upperPrice="));
        JsonObject precise = json(service.deals(new BigDecimal("49.99"), "1", "price", 0));
        assertTrue(precise.getAsJsonArray("deals").isEmpty(), "Store and exact price must still be enforced if the provider ignores its filters.");
        assertFalse(paths.getLast().getRawQuery().contains("upperPrice=50"));
        assertTrue(paths.getLast().getRawQuery().contains("storeID=1"));
    }

    @Test void supportsFreeOffersAndRoundsOnlyTheProviderQueryCeiling() {
        responder.set(exchange -> new Reply(200, "[" + deal("123", "1", "0.00", "19.99") + ","
                + deal("124", "1", "10.01", "19.99") + "]"));
        JsonObject free = json(service.deals(BigDecimal.ZERO, null, "discount", 0));
        assertEquals(1, free.getAsJsonArray("deals").size());
        assertEquals("0.00", free.getAsJsonArray("deals").get(0).getAsJsonObject().get("salePrice").getAsString());
        assertTrue(paths.getLast().getRawQuery().contains("upperPrice=0"));
        JsonObject budget = json(service.deals(new BigDecimal("9.99"), null, "price", 0));
        assertEquals(1, budget.getAsJsonArray("deals").size());
        assertTrue(paths.getLast().getRawQuery().contains("upperPrice=10"));
    }

    @Test void requestsOnePageAndUsesRawPageLengthForTheContinuationHint() {
        String full = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(index -> deal(Integer.toString(index), "3", "1.00", "20.00"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        responder.set(exchange -> new Reply(200, full));
        JsonObject result = json(service.deals(null, "all", "recent", 7));
        assertEquals(7, result.get("page").getAsInt());
        assertEquals(30, result.get("pageSize").getAsInt());
        assertTrue(result.get("hasMore").getAsBoolean(), "Filtered-out offers do not prove that the next raw provider page is empty.");
        assertTrue(result.getAsJsonArray("deals").isEmpty());
        assertEquals("pageNumber=7&pageSize=30&onSale=1&sortBy=Recent&desc=0", paths.getLast().getRawQuery());
        assertEquals(2, paths.size(), "Only metadata and one deals page should be requested; there is no auto-crawl.");
    }

    @Test void cachesByCanonicalFiltersWithoutMixingPageBudgetStoreOrSort() {
        JsonObject original = json(service.deals(new BigDecimal("10.00"), null, "rating", 0));
        clock.advance(Duration.ofMinutes(2));
        JsonObject cached = json(service.deals(new BigDecimal("10.0"), "all", "rating", 0));
        assertTrue(cached.get("cached").getAsBoolean());
        assertEquals(original.get("fetchedAt"), cached.get("fetchedAt"));
        assertEquals(2, paths.size());
        service.deals(new BigDecimal("10"), "1", "rating", 0);
        service.deals(new BigDecimal("11"), "1", "rating", 0);
        service.deals(new BigDecimal("11"), "1", "discount", 0);
        service.deals(new BigDecimal("11"), "1", "discount", 1);
        assertEquals(6, paths.size());
        assertTrue(paths.get(1).getRawQuery().contains("sortBy=DealRating&desc=0"));
        assertTrue(paths.get(4).getRawQuery().contains("sortBy=Savings&desc=0"));
        assertEquals(5, json(service.health()).get("cachedDealPages").getAsInt());
    }

    @Test void showsStalePageWithItsOriginalTimestampAndHonorsRateLimitCooldown() {
        JsonObject original = json(service.deals(null, null, "rating", 0));
        clock.advance(Duration.ofMinutes(16));
        responder.set(exchange -> new Reply(429, "limited", Map.of("Retry-After", "17")));
        JsonObject stale = json(service.deals(null, "all", "rating", 0));
        assertTrue(stale.get("stale").getAsBoolean());
        assertTrue(stale.get("cached").getAsBoolean());
        assertEquals(original.get("fetchedAt"), stale.get("fetchedAt"));
        assertFalse(stale.get("warning").getAsString().isBlank());
        assertEquals("degraded", json(service.health()).get("status").getAsString());
        assertEquals(17, assertThrows(GamePriceService.ServiceException.class,
                () -> service.deals(null, null, "rating", 1)).retryAfterSeconds());
        service.deals(null, null, "rating", 0);
        assertEquals(3, paths.size(), "Cooldown should serve stale or reject cold pages without further upstream traffic.");
        clock.advance(Duration.ofSeconds(18));
        responder.set(exchange -> new Reply(200, "[]"));
        assertFalse(json(service.deals(null, null, "rating", 0)).get("stale").getAsBoolean());
        assertEquals("healthy", json(service.health()).get("status").getAsString());
    }

    @Test void activeStoreChoicesReuseMetadataWithoutClaimingANewerFetchTime() {
        JsonObject first = json(service.stores());
        JsonArray stores = first.getAsJsonArray("stores");
        assertEquals(2, stores.size());
        assertEquals("1", stores.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("Steam", stores.get(0).getAsJsonObject().get("name").getAsString());
        clock.advance(Duration.ofHours(1));
        JsonObject cached = json(service.stores());
        assertTrue(cached.get("cached").getAsBoolean());
        assertEquals(first.get("fetchedAt"), cached.get("fetchedAt"));
        service.deals(null, null, "rating", 0);
        assertEquals(2, paths.size(), "Discovery and store choices share metadata instead of repeating requests.");
    }

    @Test void expiredStoreChoicesRemainClearlyStaleWhenMetadataRefreshFails() {
        JsonObject original = json(service.stores());
        clock.advance(Duration.ofHours(25));
        upstream.createContext("/api/1.0/stores", exchange -> {
            paths.add(exchange.getRequestURI());
            try {
                byte[] bytes = "unavailable".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        JsonObject stale = json(service.stores());
        assertTrue(stale.get("cached").getAsBoolean());
        assertTrue(stale.get("stale").getAsBoolean());
        assertEquals(original.get("fetchedAt"), stale.get("fetchedAt"));
        assertEquals(original.getAsJsonArray("stores"), stale.getAsJsonArray("stores"));
        assertEquals("degraded", json(service.health()).get("status").getAsString());
        assertTrue(json(service.stores()).get("stale").getAsBoolean());
        assertEquals(2, paths.size(), "Metadata cooldown should also prevent repeated refresh requests.");
    }

    @Test void rejectsInvalidFiltersBeforeAnyNetworkRequest() {
        for (BigDecimal price : List.of(new BigDecimal("-0.01"), new BigDecimal("1000000.01"), new BigDecimal("0.001")))
            assertEquals(400, assertThrows(GamePriceService.ServiceException.class,
                    () -> service.deals(price, null, "rating", 0)).status());
        for (String id : List.of("0", "-1", "1&upperPrice=0", "https://other.example", "999999999999999999999"))
            assertEquals(400, assertThrows(GamePriceService.ServiceException.class,
                    () -> service.deals(null, id, "rating", 0)).status());
        for (String order : List.of("Price", "rating&storeID=1", "unknown"))
            assertEquals(400, assertThrows(GamePriceService.ServiceException.class,
                    () -> service.deals(null, null, order, 0)).status());
        for (int page : List.of(-1, 1001))
            assertEquals(400, assertThrows(GamePriceService.ServiceException.class,
                    () -> service.deals(null, null, "rating", page)).status());
        assertTrue(paths.isEmpty());
    }

    @Test void rejectsMalformedRatingsPricesLinksAndOversizedPages() {
        String valid = "[" + deal("123", "1", "9.99", "19.99") + "]";
        String full = java.util.stream.IntStream.rangeClosed(1, 31)
                .mapToObj(index -> deal(Integer.toString(index), "1", "1.00", "20.00"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        for (String invalid : List.of("{}", "[broken]", valid.replace("9.99", "-1.00"),
                valid.replace("\"dealRating\":\"9.2\"", "\"dealRating\":\"10.1\""),
                valid.replace("offer%2F123%2Babc%3D", "offer%26redirect%3Devil"), full)) {
            responder.set(exchange -> new Reply(200, invalid));
            assertEquals(502, assertThrows(GamePriceService.ServiceException.class,
                    () -> service.deals(null, null, "rating", 0)).status(), invalid);
            assertEquals("degraded", json(service.health()).get("status").getAsString());
            clock.advance(Duration.ofSeconds(6));
        }
        responder.set(exchange -> new Reply(200, "[]"));
        assertTrue(json(service.deals(null, null, "rating", 0)).getAsJsonArray("deals").isEmpty());
    }

    private static String deal(String id, String store, String sale, String normal) {
        return """
                {"gameID":"%s","storeID":"%s","title":"Example Game - Deluxe Edition","steamAppID":null,
                 "salePrice":"%s","normalPrice":"%s","isOnSale":"1","savings":"50.025013","dealRating":"9.2",
                 "dealID":"offer%%2F%s%%2Babc%%3D"}
                """.formatted(id, store, sale, normal, id);
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
