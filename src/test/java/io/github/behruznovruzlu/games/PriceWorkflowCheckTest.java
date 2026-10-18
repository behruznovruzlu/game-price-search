package io.github.behruznovruzlu.games;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PriceWorkflowCheckTest {
    private static final Instant START = Instant.parse("2026-10-18T12:00:00Z");

    @Test void constructionAndRepeatedSnapshotReadsRemainUnverifiedWithoutNetworkWork() {
        Fixture fixture = new Fixture();
        try (PriceWorkflowCheck check = fixture.check()) {
            for (int read = 0; read < 100; read++) {
                JsonObject value = json(check.snapshot());
                assertEquals("unverified", value.get("status").getAsString());
                assertFalse(value.get("running").getAsBoolean());
                assertTrue(value.get("checkedAt").isJsonNull());
                assertTrue(value.get("lastSuccessAt").isJsonNull());
                assertFalse(check.isHealthy());
            }
            assertEquals(0, fixture.searches.get());
            assertEquals(0, fixture.games.get());
        }
    }

    @Test void validatesBothStagesAndPublishesTheirDurationsAndFreshness() throws Exception {
        Fixture fixture = new Fixture();
        fixture.searchResult.set(search(START).replace("\"Portal\"", "\"  pOrTaL  \""));
        try (PriceWorkflowCheck check = fixture.check()) {
            JsonObject result = requestAndWait(check);
            assertTrue(check.isHealthy());
            assertEquals("healthy", result.get("status").getAsString());
            assertEquals("Portal", result.get("query").getAsString());
            assertEquals(START.toString(), result.get("checkedAt").getAsString());
            assertEquals(START.toString(), result.get("lastSuccessAt").getAsString());
            assertEquals(START.plus(Duration.ofMinutes(20)).toString(), result.get("validUntil").getAsString());
            assertTrue(result.get("searchMs").getAsDouble() >= 0);
            assertTrue(result.get("offersMs").getAsDouble() >= 0);
            assertEquals(1, result.get("offerCount").getAsInt());
            assertTrue(result.get("error").isJsonNull());
            assertEquals(1, fixture.searches.get());
            assertEquals(1, fixture.games.get());
        }
    }

    @Test void rejectsSuccessfulHttpPayloadsThatDoNotFindTheExpectedTitle() throws Exception {
        Fixture fixture = new Fixture();
        try (PriceWorkflowCheck check = fixture.check()) {
            for (String wrong : List.of(
                    search(START).replace("Portal", "Portal 2"),
                    search(START).replace("[{\"id\":\"82\",\"title\":\"Portal\"}]", "[]"),
                    search(START).replace("\"82\"", "\"0\""),
                    search(START).replace("\"USD\"", "\"GBP\""),
                    search(START).replace("\"cached\":false", "\"cached\":true"),
                    search(START).replace("\"stale\":false", "\"stale\":true"),
                    "{broken")) {
                fixture.searchResult.set(wrong);
                JsonObject failed = requestAndWait(check);
                assertEquals("failed", failed.get("status").getAsString(), wrong);
                assertFalse(check.isHealthy());
                assertFalse(failed.get("error").getAsString().isBlank());
                assertTrue(failed.get("lastSuccessAt").isJsonNull());
            }
            assertEquals(0, fixture.games.get(), "An invalid search must not select or fetch a different game.");
        }
    }

    @Test void rejectsMissingInvalidOrWrongGameOffersRatherThanTreatingHttp200AsHealthy() throws Exception {
        Fixture fixture = new Fixture();
        String valid = details(START);
        try (PriceWorkflowCheck check = fixture.check()) {
            for (String wrong : List.of(
                    valid.replace("[{\"active\":true,\"price\":0.99,\"storeName\":\"Steam\"}]", "[]"),
                    valid.replace("\"id\":\"82\"", "\"id\":\"83\""),
                    valid.replace("\"title\":\"Portal\"", "\"title\":\"Another game\""),
                    valid.replace("\"active\":true", "\"active\":false"),
                    valid.replace("\"price\":0.99", "\"price\":-1"),
                    valid.replace("\"price\":0.99", "\"price\":\"0.99\""),
                    valid.replace("\"Steam\"", "\" \""),
                    valid.replace("\"USD\"", "\"GBP\""),
                    valid.replace("\"cached\":false", "\"cached\":true"),
                    valid.replace("\"stale\":false", "\"stale\":true"))) {
                fixture.gameResult.set(wrong);
                JsonObject failed = requestAndWait(check);
                assertEquals("failed", failed.get("status").getAsString(), wrong);
                assertFalse(check.isHealthy());
                assertEquals(0, failed.get("offerCount").getAsInt());
            }
        }
    }

    @Test void aLaterFailureImmediatelyInvalidatesPriorSuccessButKeepsItsHistoricalTime() throws Exception {
        Fixture fixture = new Fixture();
        try (PriceWorkflowCheck check = fixture.check()) {
            requestAndWait(check);
            fixture.clock.advance(Duration.ofMinutes(1));
            fixture.gameFailure.set(new GamePriceService.ServiceException(504, "Price lookup timed out.", 5));
            JsonObject failed = requestAndWait(check);
            assertFalse(check.isHealthy());
            assertEquals("failed", failed.get("status").getAsString());
            assertEquals(START.plus(Duration.ofMinutes(1)).toString(), failed.get("checkedAt").getAsString());
            assertEquals(START.toString(), failed.get("lastSuccessAt").getAsString());
            assertTrue(failed.get("validUntil").isJsonNull());
            assertEquals("Price lookup timed out.", failed.get("error").getAsString());

            fixture.gameFailure.set(null);
            assertEquals("healthy", requestAndWait(check).get("status").getAsString());
            assertEquals(START.plus(Duration.ofMinutes(1)).toString(), json(check.snapshot()).get("lastSuccessAt").getAsString());
        }
    }

    @Test void freshnessExpiresWithoutAnySnapshotOrHealthReadStartingAnotherLookup() throws Exception {
        Fixture fixture = new Fixture();
        try (PriceWorkflowCheck check = fixture.check()) {
            requestAndWait(check);
            fixture.clock.advance(Duration.ofMinutes(20).minusMillis(1));
            assertTrue(check.isHealthy());
            fixture.clock.advance(Duration.ofMillis(1));
            for (int read = 0; read < 100; read++) {
                assertFalse(check.isHealthy());
                assertEquals("stale", json(check.snapshot()).get("status").getAsString());
            }
            assertEquals(1, fixture.searches.get());
            assertEquals(1, fixture.games.get());
        }
    }

    @Test void localContentionDefersForThirtySecondsWithoutErasingAStillFreshSuccess() throws Exception {
        Fixture fixture = new Fixture();
        try (PriceWorkflowCheck check = fixture.check()) {
            check.start();
            JsonObject healthy = awaitCompleted(check);
            assertEquals("healthy", healthy.get("status").getAsString());
            fixture.clock.advance(Duration.ofMinutes(1));
            fixture.searchFailure.set(new GamePriceService.ServiceException(503, "Another price lookup is in progress.", 1, true));
            JsonObject deferred = requestAndWait(check);
            assertEquals("healthy", deferred.get("status").getAsString());
            assertTrue(check.isHealthy());
            assertTrue(deferred.get("deferred").getAsBoolean());
            assertTrue(deferred.get("error").isJsonNull());
            assertEquals(healthy.get("checkedAt"), deferred.get("checkedAt"));
            assertEquals(START.plusSeconds(90).toString(), deferred.get("nextCheckAt").getAsString());
            assertEquals(1, fixture.games.get());

            fixture.clock.advance(Duration.ofMinutes(20));
            assertFalse(check.isHealthy());
            assertEquals("stale", json(check.snapshot()).get("status").getAsString());
        }
    }

    @Test void contentionBeforeTheFirstCompletedWorkflowRemainsUnverified() throws Exception {
        Fixture fixture = new Fixture();
        fixture.searchFailure.set(new GamePriceService.ServiceException(503, "Busy", 1, true));
        try (PriceWorkflowCheck check = fixture.check()) {
            check.start();
            JsonObject deferred = awaitCompleted(check);
            assertEquals("unverified", deferred.get("status").getAsString());
            assertTrue(deferred.get("deferred").getAsBoolean());
            assertTrue(deferred.get("checkedAt").isJsonNull());
            assertEquals(START.plusSeconds(30).toString(), deferred.get("nextCheckAt").getAsString());
            assertFalse(check.isHealthy());
            assertEquals(0, fixture.games.get());
        }
    }

    @Test void startIsAsynchronousChecksDoNotOverlapAndCloseInterruptsOwnedWork() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> blockedSearch = query -> {
            calls.incrementAndGet();
            arrived.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException stopping) { interrupted.set(true); Thread.currentThread().interrupt(); }
            return search(fixture.clock.instant());
        };
        PriceWorkflowCheck check = new PriceWorkflowCheck(fixture.clock, Duration.ofMinutes(15), Duration.ofMinutes(20),
                blockedSearch, fixture.game());
        try {
            check.start();
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            assertEquals("unverified", json(check.snapshot()).get("status").getAsString());
            assertTrue(json(check.snapshot()).get("running").getAsBoolean());
            check.start();
            for (int attempt = 0; attempt < 10; attempt++) assertFalse(check.requestCheck());
            assertEquals(1, calls.get());
            check.close();
            assertTrue(interrupted.get());
            assertFalse(check.requestCheck());
            assertFalse(check.isHealthy());
            assertEquals(0, fixture.games.get(), "Closing during search must not start the next provider stage.");
        } finally {
            release.countDown();
            check.close();
        }
    }

    private static JsonObject requestAndWait(PriceWorkflowCheck check) throws Exception {
        assertTrue(check.requestCheck());
        return awaitIdle(check);
    }

    private static JsonObject awaitCompleted(PriceWorkflowCheck check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        synchronized (check) {
            while (true) {
                JsonObject value = json(check.snapshot());
                if (!value.get("running").getAsBoolean()
                        && (!value.get("checkedAt").isJsonNull() || value.get("deferred").getAsBoolean())) return value;
                if (System.nanoTime() >= deadline) fail("The scheduled workflow did not finish: " + value);
                check.wait(20);
            }
        }
    }

    private static JsonObject awaitIdle(PriceWorkflowCheck check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        synchronized (check) {
            while (json(check.snapshot()).get("running").getAsBoolean()) {
                if (System.nanoTime() >= deadline) fail("The workflow did not finish.");
                check.wait(20);
            }
            return json(check.snapshot());
        }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    private static String search(Instant at) {
        return "{\"currency\":\"USD\",\"fetchedAt\":\"" + at + "\",\"cached\":false,\"stale\":false,"
                + "\"games\":[{\"id\":\"82\",\"title\":\"Portal\"}]}";
    }

    private static String details(Instant at) {
        return "{\"id\":\"82\",\"title\":\"Portal\",\"currency\":\"USD\",\"fetchedAt\":\"" + at
                + "\",\"cached\":false,\"stale\":false,\"offers\":[{\"active\":true,\"price\":0.99,\"storeName\":\"Steam\"}]}";
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock(START);
        final AtomicInteger searches = new AtomicInteger();
        final AtomicInteger games = new AtomicInteger();
        final AtomicReference<String> searchResult = new AtomicReference<>();
        final AtomicReference<String> gameResult = new AtomicReference<>();
        final AtomicReference<RuntimeException> searchFailure = new AtomicReference<>();
        final AtomicReference<RuntimeException> gameFailure = new AtomicReference<>();

        PriceWorkflowCheck check() {
            return new PriceWorkflowCheck(clock, Duration.ofMinutes(15), Duration.ofMinutes(20), query -> {
                assertEquals("Portal", query);
                searches.incrementAndGet();
                RuntimeException failure = searchFailure.get();
                if (failure != null) throw failure;
                return searchResult.get() == null ? search(clock.instant()) : searchResult.get();
            }, game());
        }

        Function<String, String> game() {
            return id -> {
                assertEquals("82", id);
                games.incrementAndGet();
                RuntimeException failure = gameFailure.get();
                if (failure != null) throw failure;
                return gameResult.get() == null ? details(clock.instant()) : gameResult.get();
            };
        }
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
