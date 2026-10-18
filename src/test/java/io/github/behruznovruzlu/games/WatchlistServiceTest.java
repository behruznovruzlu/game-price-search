package io.github.behruznovruzlu.games;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistServiceTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));

    @Test void savesToTheDataFolderWithoutCallingTheProviderAndSurvivesRestart() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> lookup = id -> { calls.incrementAndGet(); return response(id, clock.instant(), "9.99", false); };
        try (WatchlistService service = service(lookup)) {
            JsonObject result = json(service.upsert("123", "Example <Game>", new BigDecimal("12.5")));
            assertEquals("USD", result.get("currency").getAsString());
            assertEquals(1800, result.get("refreshIntervalSeconds").getAsInt());
            assertEquals(0, calls.get(), "saving alone must not make a hidden upstream request");
            assertEquals("Example <Game>", first(result).get("title").getAsString());
            assertEquals(new BigDecimal("12.50"), first(result).get("targetPrice").getAsBigDecimal());
            assertTrue(first(result).get("lastPrice").isJsonNull());
            service.upsert("456", "Saved without an alert", null);
        }
        assertTrue(Files.isRegularFile(directory.resolve("watchlist.json")));
        try (WatchlistService reopened = service(lookup)) {
            assertEquals(2, json(reopened.snapshot()).getAsJsonArray("items").size());
            assertEquals("Example <Game>", first(reopened).get("title").getAsString());
            assertFalse(first(reopened).get("targetReached").getAsBoolean());
            assertEquals(0, calls.get(), "reopening without start must not call the provider");
        }
    }

    @Test void acknowledgedHitDoesNotRepeatAcrossCachedReadsNewFetchesOrRestartUntilPriceCrossesAgain() throws Exception {
        AtomicReference<String> price = new AtomicReference<>("9.99");
        AtomicReference<Instant> fetchedAt = new AtomicReference<>(clock.instant());
        Function<String, String> lookup = id -> response(id, fetchedAt.get(), price.get(), false);
        try (WatchlistService service = service(lookup)) {
            service.upsert("123", "Example", new BigDecimal("10"));
            refresh(service);
            assertTrue(first(service).get("targetReached").getAsBoolean());
            assertTrue(first(service).get("notificationUnread").getAsBoolean());
            service.acknowledge("123");
            refresh(service);
            assertFalse(first(service).get("notificationUnread").getAsBoolean());
            clock.advance(Duration.ofMinutes(30));
            fetchedAt.set(clock.instant());
            refresh(service);
            assertFalse(first(service).get("notificationUnread").getAsBoolean(), "a new observation still below target must not repeat the alert");
        }
        try (WatchlistService service = service(lookup)) {
            refresh(service);
            assertTrue(first(service).get("targetReached").getAsBoolean());
            assertFalse(first(service).get("notificationUnread").getAsBoolean());
            clock.advance(Duration.ofMinutes(30));
            fetchedAt.set(clock.instant());
            price.set("12.00");
            refresh(service);
            assertFalse(first(service).get("targetReached").getAsBoolean());
            clock.advance(Duration.ofMinutes(30));
            fetchedAt.set(clock.instant());
            price.set("0.00");
            refresh(service);
            assertTrue(first(service).get("targetReached").getAsBoolean(), "a legitimate free offer meets the target");
            assertTrue(first(service).get("notificationUnread").getAsBoolean());
        }
    }

    @Test void changingTargetReevaluatesAnOtherwiseIdenticalFreshCachedPrice() throws Exception {
        try (WatchlistService service = service(id -> response(id, clock.instant(), "15.00", false))) {
            service.upsert("123", "Example", new BigDecimal("10"));
            refresh(service);
            assertFalse(first(service).get("targetReached").getAsBoolean());
            service.upsert("123", "Example", new BigDecimal("20"));
            refresh(service);
            assertTrue(first(service).get("notificationUnread").getAsBoolean());
            service.acknowledge("123");
            service.upsert("123", "Renamed title", new BigDecimal("20.00"));
            refresh(service);
            assertFalse(first(service).get("notificationUnread").getAsBoolean(), "title-only changes or equivalent amounts must not reset alerts");
            service.upsert("123", "Example", null);
            assertFalse(first(service).get("targetReached").getAsBoolean());
        }
    }

    @Test void staleDataKeepsItsOriginalTimestampAndCannotRaiseAnAlert() throws Exception {
        Instant original = clock.instant().minus(Duration.ofHours(1));
        AtomicReference<Boolean> stale = new AtomicReference<>(true);
        try (WatchlistService service = service(id -> response(id, original, "4.99", stale.get()))) {
            service.upsert("123", "Example", new BigDecimal("10"));
            refresh(service);
            JsonObject item = first(service);
            assertEquals(original.toString(), item.get("lastSuccessAt").getAsString());
            assertEquals(clock.instant().toString(), item.get("lastCheckedAt").getAsString());
            assertEquals(new BigDecimal("4.99"), item.get("lastPrice").getAsBigDecimal());
            assertFalse(item.get("targetReached").getAsBoolean());
            assertFalse(item.get("notificationUnread").getAsBoolean());
            assertTrue(item.get("error").getAsString().contains("no new alert"));
            stale.set(false);
            refresh(service);
            assertTrue(first(service).get("notificationUnread").getAsBoolean(), "an actual successful observation may evaluate the target");
        }
    }

    @Test void providerFailurePreservesTheLastPriceAndDoesNotInventARefreshTimestamp() throws Exception {
        AtomicReference<Boolean> failing = new AtomicReference<>(false);
        Function<String, String> lookup = id -> {
            if (failing.get()) throw new GamePriceService.ServiceException(503, "CheapShark is limiting requests. Please wait.", 60);
            return response(id, clock.instant(), "9.99", false);
        };
        try (WatchlistService service = service(lookup)) {
            service.upsert("123", "Example", new BigDecimal("8"));
            refresh(service);
            String successAt = first(service).get("lastSuccessAt").getAsString();
            failing.set(true);
            clock.advance(Duration.ofMinutes(30));
            refresh(service);
            JsonObject item = first(service);
            assertEquals(new BigDecimal("9.99"), item.get("lastPrice").getAsBigDecimal());
            assertEquals("Steam", item.get("lastStore").getAsString());
            assertEquals(successAt, item.get("lastSuccessAt").getAsString());
            assertEquals(clock.instant().toString(), item.get("lastCheckedAt").getAsString());
            assertTrue(item.get("error").getAsString().contains("limiting requests"));
            assertFalse(item.get("notificationUnread").getAsBoolean());
        }
    }

    @Test void noActiveOffersAreDifferentFromAnOutageAndCannotMeetTheTarget() throws Exception {
        AtomicReference<Boolean> empty = new AtomicReference<>(false);
        try (WatchlistService service = service(id -> {
            JsonObject value = json(response(id, clock.instant(), "9.99", false));
            if (empty.get()) value.getAsJsonArray("offers").get(0).getAsJsonObject().addProperty("active", false);
            return value.toString();
        })) {
            service.upsert("123", "Example", new BigDecimal("10"));
            refresh(service);
            assertTrue(first(service).get("targetReached").getAsBoolean());
            String priceObservedAt = first(service).get("lastSuccessAt").getAsString();
            empty.set(true);
            clock.advance(Duration.ofMinutes(30));
            refresh(service);
            assertFalse(first(service).get("targetReached").getAsBoolean());
            assertFalse(first(service).get("notificationUnread").getAsBoolean());
            assertEquals("No active store offers are available for this game.", first(service).get("error").getAsString());
            assertEquals(new BigDecimal("9.99"), first(service).get("lastPrice").getAsBigDecimal(), "retain the historical known price but explain there is no current offer");
            assertEquals(priceObservedAt, first(service).get("lastSuccessAt").getAsString(), "no offers must not make a retained old price look freshly observed");
        }
    }

    @Test void validatesTargetsAndLimitsTheWatchlistWithoutLosingExistingGames() throws Exception {
        try (WatchlistService service = service(id -> response(id, clock.instant(), "9.99", false))) {
            assertThrows(IllegalArgumentException.class, () -> service.upsert("0", "Example", null));
            assertThrows(IllegalArgumentException.class, () -> service.upsert("123", "\nExample", null));
            for (String bad : new String[]{"-1", "1.001", "1000000.01"})
                assertThrows(IllegalArgumentException.class, () -> service.upsert("123", "Example", new BigDecimal(bad)));
            for (int id = 1; id <= 50; id++) service.upsert(Integer.toString(id), "<".repeat(1000), null);
            assertTrue(Files.size(directory.resolve("watchlist.json")) > 256 * 1024,
                    "the bounded format must accommodate all 50 valid titles even when Gson escapes every character");
            assertThrows(IllegalArgumentException.class, () -> service.upsert("51", "Game 51", null));
            service.upsert("1", "Updated", new BigDecimal("1.2300"));
            assertEquals(50, json(service.snapshot()).getAsJsonArray("items").size());
            service.remove("2");
            service.upsert("51", "Game 51", null);
            assertEquals(50, json(service.snapshot()).getAsJsonArray("items").size());
        }
    }

    @Test void refreshIsSingleFlightAndRemovalCannotBeUndoneByAnInFlightLookup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (WatchlistService service = service(id -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return response(id, clock.instant(), "4.99", false);
        })) {
            service.upsert("123", "Example", new BigDecimal("10"));
            assertTrue(service.requestRefresh());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(service.requestRefresh());
            service.remove("123");
            release.countDown();
            waitUntilIdle(service);
            assertEquals(1, calls.get());
            assertTrue(json(service.snapshot()).getAsJsonArray("items").isEmpty(), "the in-flight result must not resurrect a removed game");
        } finally { release.countDown(); }
    }

    @Test void gamesAddedDuringAnActiveCheckGetOneFollowUpPassWithoutQueuingRefreshClicks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (WatchlistService service = service(id -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); }
            return response(id, clock.instant(), "4.99", false);
        })) {
            service.upsert("123", "First game", new BigDecimal("10"));
            assertTrue(service.requestRefresh());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.upsert("456", "Second game", new BigDecimal("5"));
            assertFalse(service.requestRefresh());
            assertFalse(service.requestRefresh());
            release.countDown();
            waitUntilIdle(service);
            assertEquals(3, calls.get(), "one initial lookup plus a single two-game follow-up pass");
            var items = json(service.snapshot()).getAsJsonArray("items");
            assertEquals(2, items.size());
            assertTrue(items.get(1).getAsJsonObject().get("targetReached").getAsBoolean());
            assertEquals(new BigDecimal("4.99"), items.get(1).getAsJsonObject().get("lastPrice").getAsBigDecimal());
        } finally { release.countDown(); }
    }

    @Test void aTargetEditedDuringRefreshUsesTheCurrentTarget() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (WatchlistService service = service(id -> {
            entered.countDown();
            await(release);
            return response(id, clock.instant(), "15.00", false);
        })) {
            service.upsert("123", "Example", new BigDecimal("10"));
            service.requestRefresh();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.upsert("123", "Example", new BigDecimal("20"));
            release.countDown();
            waitUntilIdle(service);
            assertTrue(first(service).get("notificationUnread").getAsBoolean());
            assertEquals(new BigDecimal("20.00"), first(service).get("targetPrice").getAsBigDecimal());
        } finally { release.countDown(); }
    }

    @Test void corruptOrOversizedStateIsPreservedAndASecondInstanceCannotOverwriteIt() throws Exception {
        Path file = directory.resolve("watchlist.json");
        byte[] corrupt = "{not valid json".getBytes(StandardCharsets.UTF_8);
        Files.write(file, corrupt);
        IOException error = assertThrows(IOException.class, () -> service(id -> "unused"));
        assertTrue(error.getMessage().contains("preserved"));
        assertArrayEquals(corrupt, Files.readAllBytes(file));
        Files.writeString(file, " ".repeat(512 * 1024 + 1));
        assertThrows(IOException.class, () -> service(id -> "unused"));
        assertEquals(512 * 1024 + 1, Files.size(file));
        Files.delete(file);
        try (WatchlistService first = service(id -> "unused")) {
            first.upsert("123", "Example", null);
            IOException locked = assertThrows(IOException.class, () -> service(id -> "unused"));
            assertTrue(locked.getMessage().contains("already open"));
        }
        try (WatchlistService reopened = service(id -> "unused")) {
            assertEquals("123", first(reopened).get("id").getAsString());
        }
    }

    @Test void aFailedWriteRollsBackTheMutationAndExposesTheProblem() throws Exception {
        try (WatchlistService service = service(id -> "unused")) {
            // A directory at the expected file location is a deterministic cross-platform failed replacement.
            Files.createDirectory(directory.resolve("watchlist.json"));
            Files.writeString(directory.resolve("watchlist.json").resolve("keep"), "do not replace this directory");
            assertThrows(IllegalStateException.class, () -> service.upsert("123", "Example", null));
            JsonObject result = json(service.snapshot());
            assertTrue(result.getAsJsonArray("items").isEmpty());
            assertTrue(result.get("storageError").getAsString().contains("could not be saved"));
            Files.delete(directory.resolve("watchlist.json").resolve("keep"));
            Files.delete(directory.resolve("watchlist.json"));
            service.upsert("123", "Example", null);
            assertTrue(json(service.snapshot()).get("storageError").isJsonNull());
        }
    }

    @Test void explicitStartChecksRestoredItemsButConstructionDoesNotAndShutdownReleasesOwnership() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> lookup = id -> { calls.incrementAndGet(); return response(id, clock.instant(), "4.99", false); };
        WatchlistService original = service(lookup);
        original.upsert("123", "Example", new BigDecimal("10"));
        assertEquals(0, calls.get());
        original.close();
        original.close();
        assertFalse(original.requestRefresh());
        try (WatchlistService reopened = service(lookup)) {
            reopened.start();
            waitUntilIdle(reopened);
            assertEquals(1, calls.get());
            assertTrue(first(reopened).get("notificationUnread").getAsBoolean());
            reopened.start();
            assertEquals(1, calls.get(), "start must be idempotent");
        }
    }

    private WatchlistService service(Function<String, String> lookup) throws IOException {
        return new WatchlistService(directory, clock, Duration.ofMinutes(30), lookup);
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject first(WatchlistService service) { return first(json(service.snapshot())); }
    private static JsonObject first(JsonObject value) { return value.getAsJsonArray("items").get(0).getAsJsonObject(); }

    private static void refresh(WatchlistService service) throws InterruptedException {
        assertTrue(service.requestRefresh());
        waitUntilIdle(service);
    }

    private static void waitUntilIdle(WatchlistService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (json(service.snapshot()).get("checking").getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(json(service.snapshot()).get("checking").getAsBoolean(), "refresh did not finish within five seconds");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lookup timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static String response(String id, Instant at, String price, boolean stale) {
        return "{\"id\":\"" + id + "\",\"title\":\"Example Game\",\"steamAppId\":\"570\",\"currency\":\"USD\",\"fetchedAt\":\"" + at
                + "\",\"cached\":false,\"stale\":" + stale + ",\"offers\":[{\"active\":true,\"storeName\":\"Steam\",\"price\":" + price + "}]}";
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;
        MutableClock(Instant value) { current = value; }
        void advance(Duration value) { current = current.plus(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
