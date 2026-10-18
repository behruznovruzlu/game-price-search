package io.github.behruznovruzlu.games;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** An opt-in backend/provider workflow check. Reading its result never initiates a lookup. */
public final class PriceWorkflowCheck implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create();
    private static final String QUERY = "Portal";
    private static final Duration BUSY_RETRY = Duration.ofSeconds(30);
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
    private final Clock clock;
    private final Duration interval;
    private final Duration freshness;
    private final Function<String, String> search;
    private final Function<String, String> game;
    private final ScheduledExecutorService worker;
    private ScheduledFuture<?> nextTask;
    private String status = "unverified";
    private Instant checkedAt;
    private Instant lastSuccessAt;
    private Instant nextCheckAt;
    private Instant validUntil;
    private Double searchMs;
    private Double offersMs;
    private int offerCount;
    private String error;
    private boolean deferred;
    private boolean started;
    private boolean running;
    private boolean closed;

    public PriceWorkflowCheck(GamePriceService games) {
        this(Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofMinutes(20),
                Objects.requireNonNull(games)::freshSearch, games::freshGame);
    }

    // Separate time and lookup inputs keep all workflow tests independent of public API availability.
    PriceWorkflowCheck(Clock clock, Duration interval, Duration freshness,
                       Function<String, String> search, Function<String, String> game) {
        this.clock = Objects.requireNonNull(clock);
        this.interval = Objects.requireNonNull(interval);
        this.freshness = Objects.requireNonNull(freshness);
        this.search = Objects.requireNonNull(search);
        this.game = Objects.requireNonNull(game);
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofDays(7)) > 0
                || freshness.compareTo(interval) < 0 || freshness.compareTo(Duration.ofDays(14)) > 0)
            throw new IllegalArgumentException("Use a bounded positive interval and freshness at least as long as the interval.");
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "price-workflow-check");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Start once after the local server is ready; no caller waits for an upstream lookup here. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("The workflow check is closed.");
        if (started) return;
        started = true;
        if (!running) schedule(Duration.ZERO);
    }

    /** An explicit check cancels its pending timer; concurrent requests never queue extra cycles. */
    public synchronized boolean requestCheck() {
        if (closed || running) return false;
        if (nextTask != null) nextTask.cancel(false);
        nextTask = null;
        nextCheckAt = null;
        running = true;
        try { worker.execute(this::check); }
        catch (RejectedExecutionException stopping) { running = false; return false; }
        return true;
    }

    public synchronized boolean isHealthy() { return currentStatus().equals("healthy"); }

    public synchronized String snapshot() {
        JsonObject value = new JsonObject();
        value.addProperty("enabled", true);
        value.addProperty("status", currentStatus());
        value.addProperty("running", running);
        value.addProperty("query", QUERY);
        value.add("checkedAt", instant(checkedAt));
        value.add("lastSuccessAt", instant(lastSuccessAt));
        value.add("nextCheckAt", instant(nextCheckAt));
        value.add("validUntil", instant(validUntil));
        value.addProperty("searchMs", searchMs);
        value.addProperty("offersMs", offersMs);
        value.addProperty("offerCount", offerCount);
        value.addProperty("error", error);
        value.addProperty("deferred", deferred);
        value.addProperty("intervalSeconds", interval.toSeconds());
        value.addProperty("freshnessSeconds", freshness.toSeconds());
        return JSON.toJson(value);
    }

    private String currentStatus() {
        if (closed) return "failed";
        if (status.equals("healthy") && (validUntil == null || !clock.instant().isBefore(validUntil))) return "stale";
        return status;
    }

    private synchronized void schedule(Duration delay) {
        if (closed || !started) return;
        nextCheckAt = clock.instant().plus(delay);
        nextTask = worker.schedule(() -> {
            synchronized (PriceWorkflowCheck.this) {
                if (closed || running) return;
                nextTask = null;
                nextCheckAt = null;
                running = true;
            }
            check();
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void check() {
        Double searchDuration = null;
        Double offersDuration = null;
        int count = 0;
        String failure = null;
        boolean busy = false;
        boolean passed = false;
        long phaseStart = System.nanoTime();
        boolean searching = true;
        try {
            ensureChecking();
            String found = search.apply(QUERY);
            JsonObject results = freshResponse(found);
            String id = selectedId(results);
            searchDuration = elapsed(phaseStart);
            searching = false;
            phaseStart = System.nanoTime();
            ensureChecking();
            JsonObject details = freshResponse(game.apply(id));
            count = validateOffers(id, details);
            offersDuration = elapsed(phaseStart);
            passed = true;
        } catch (GamePriceService.ServiceException unavailable) {
            busy = unavailable.isBusy();
            failure = busy ? null : safeFailure(unavailable);
        } catch (RuntimeException invalid) {
            failure = safeFailure(invalid);
        } finally {
            if (searching && searchDuration == null) searchDuration = elapsed(phaseStart);
            if (!searching && offersDuration == null) offersDuration = elapsed(phaseStart);
            synchronized (this) {
                if (!closed) {
                    if (busy) {
                        // Contention says nothing about the provider; keep the previous result until it expires.
                        deferred = true;
                    } else {
                        deferred = false;
                        checkedAt = clock.instant();
                        searchMs = searchDuration;
                        offersMs = offersDuration;
                        offerCount = count;
                        error = passed ? null : failure == null ? "The price workflow did not finish." : failure;
                        status = passed ? "healthy" : "failed";
                        if (passed) {
                            lastSuccessAt = checkedAt;
                            validUntil = checkedAt.plus(freshness);
                        } else {
                            validUntil = null;
                        }
                    }
                }
                running = false;
                if (started && !closed) schedule(busy ? BUSY_RETRY : interval);
                notifyAll();
            }
        }
    }

    private synchronized void ensureChecking() {
        if (closed || Thread.currentThread().isInterrupted()) throw new IllegalStateException("The workflow check was stopped.");
    }

    private JsonObject freshResponse(String response) {
        if (response == null || response.length() > 1024 * 1024)
            throw invalid("The workflow returned invalid price data.");
        JsonElement parsed = JSON.fromJson(response, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) throw invalid("The workflow returned invalid price data.");
        JsonObject value = parsed.getAsJsonObject();
        if (!text(value, "currency").equals("USD") || bool(value, "stale") || bool(value, "cached"))
            throw invalid("The workflow did not return fresh USD price data.");
        Instant fetched = Instant.parse(text(value, "fetchedAt"));
        if (fetched.isBefore(Instant.EPOCH) || fetched.isAfter(clock.instant().plus(Duration.ofMinutes(5))))
            throw invalid("The workflow returned an invalid observation time.");
        return value;
    }

    private String selectedId(JsonObject results) {
        JsonArray matches = array(results, "games", 60);
        for (JsonElement raw : matches) {
            if (!raw.isJsonObject()) throw invalid("The title search returned invalid games.");
            JsonObject match = raw.getAsJsonObject();
            if (text(match, "title").strip().equalsIgnoreCase(QUERY)) {
                String id = text(match, "id");
                if (!id.matches("[1-9][0-9]{0,19}")) throw invalid("The title search returned an invalid game ID.");
                return id;
            }
        }
        throw invalid("The title search did not return Portal.");
    }

    private int validateOffers(String id, JsonObject details) {
        if (!text(details, "id").equals(id) || !text(details, "title").strip().equalsIgnoreCase(QUERY))
            throw invalid("The offers did not belong to the selected Portal game.");
        JsonArray offers = array(details, "offers", 60);
        int active = 0;
        for (JsonElement raw : offers) {
            if (!raw.isJsonObject()) throw invalid("The workflow returned invalid store offers.");
            JsonObject offer = raw.getAsJsonObject();
            if (!bool(offer, "active")) continue;
            if (text(offer, "storeName").isBlank()) throw invalid("An active offer had no store name.");
            JsonElement rawPrice = offer.get("price");
            if (rawPrice == null || !rawPrice.isJsonPrimitive() || !rawPrice.getAsJsonPrimitive().isNumber()
                    || rawPrice.getAsString().length() > 40)
                throw invalid("An active offer had an invalid price.");
            BigDecimal price = rawPrice.getAsBigDecimal();
            if (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0)
                throw invalid("An active offer had an invalid price.");
            active++;
        }
        if (active == 0) throw invalid("Portal had no active store offers.");
        return active;
    }

    private static JsonArray array(JsonObject value, String field, int maximum) {
        JsonElement raw = value.get(field);
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() > maximum)
            throw invalid("The workflow returned an invalid result list.");
        return raw.getAsJsonArray();
    }

    private static String text(JsonObject value, String field) {
        JsonElement raw = value.get(field);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString())
            throw invalid("The workflow returned a missing or invalid text field.");
        String result = raw.getAsString();
        if (result.length() > 1000 || result.codePoints().anyMatch(Character::isISOControl))
            throw invalid("The workflow returned invalid text.");
        return result;
    }

    private static boolean bool(JsonObject value, String field) {
        JsonElement raw = value.get(field);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean())
            throw invalid("The workflow returned a missing or invalid boolean field.");
        return raw.getAsBoolean();
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static String safeFailure(RuntimeException failure) {
        if (failure instanceof GamePriceService.ServiceException || failure instanceof IllegalArgumentException) {
            String message = failure.getMessage();
            if (message != null && !message.isBlank()) {
                String clean = message.replaceAll("[\\p{Cntrl}]", " ");
                return clean.substring(0, Math.min(300, clean.length()));
            }
        }
        return "The price workflow could not be completed. Its next scheduled check will try again.";
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static JsonElement instant(Instant value) { return value == null ? JsonNull.INSTANCE : JSON.toJsonTree(value.toString()); }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            error = "The workflow check is closed.";
            nextCheckAt = null;
            running = false;
            if (nextTask != null) nextTask.cancel(false);
            notifyAll();
        }
        worker.shutdownNow();
        try { worker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
