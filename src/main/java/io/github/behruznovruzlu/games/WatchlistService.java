package io.github.behruznovruzlu.games;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** A single-user, bounded watchlist. Only an open application checks prices or raises in-app alerts. */
public final class WatchlistService implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create();
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(30);
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
    private static final int MAX_ITEMS = 50;
    // Fifty 1,000-character titles can expand to 300 KB through JSON/HTML escaping alone.
    private static final int MAX_STATE_BYTES = 512 * 1024;
    private final Path directory;
    private final Path stateFile;
    private final Clock clock;
    private final Duration interval;
    private final Function<String, String> lookup;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final ScheduledExecutorService worker;
    private Map<String, Item> items = new LinkedHashMap<>();
    private Instant lastCycleAt;
    private String storageError;
    private long nextGeneration;
    private boolean checking;
    private boolean refreshPending;
    private boolean started;
    private boolean closed;

    public WatchlistService(Path dataDirectory, GamePriceService games) throws IOException {
        this(dataDirectory, Clock.systemUTC(), DEFAULT_INTERVAL, Objects.requireNonNull(games)::game);
    }

    // A supplied clock and lookup keep persistence, alert transitions and races independently testable.
    WatchlistService(Path dataDirectory, Clock clock, Duration interval, Function<String, String> lookup) throws IOException {
        this.directory = Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize();
        this.stateFile = directory.resolve("watchlist.json");
        this.clock = Objects.requireNonNull(clock);
        this.interval = Objects.requireNonNull(interval);
        this.lookup = Objects.requireNonNull(lookup);
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("The watchlist refresh interval must be between one second and seven days.");
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(".watchlist.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException conflict) { /* Another instance in this JVM owns the directory. */ }
            if (acquired == null) throw new IOException("This watchlist is already open in another application. Close that instance first.");
            load();
        } catch (IOException | RuntimeException error) {
            if (acquired != null) acquired.release();
            channel.close();
            throw error;
        }
        this.lockChannel = channel;
        this.directoryLock = acquired;
        this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "watchlist-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Start once after the local server is ready. Construction and HTTP mutations do not call the provider. */
    public synchronized void start() {
        ensureOpen();
        if (started) return;
        started = true;
        worker.scheduleWithFixedDelay(this::requestRefresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        if (!items.isEmpty()) requestRefresh();
    }

    public synchronized String snapshot() { return JSON.toJson(snapshotObject()); }

    /** A null target saves a game without an alert; USD targets have at most two decimal places. */
    public synchronized String upsert(String id, String title, BigDecimal targetPrice) {
        ensureOpen();
        id = validId(id);
        title = validText(title, 1000, "Choose a valid game title.");
        targetPrice = validTarget(targetPrice);
        Item previous = items.get(id);
        boolean needsCheck = previous == null || !sameAmount(previous.targetPrice(), targetPrice);
        if (previous == null && items.size() >= MAX_ITEMS)
            throw new IllegalArgumentException("The watchlist can hold up to 50 games. Remove a game before adding another.");
        Item next;
        if (previous == null) {
            next = new Item(id, title, null, targetPrice, null, null, null, null, null,
                    false, false, null, ++nextGeneration);
        } else {
            boolean changedTarget = !sameAmount(previous.targetPrice(), targetPrice);
            next = new Item(id, title, previous.steamAppId(), targetPrice, previous.lastPrice(), previous.lastStore(),
                    previous.lastCheckedAt(), previous.lastSuccessAt(), previous.error(),
                    !changedTarget && previous.targetReached(), !changedTarget && previous.notificationUnread(),
                    changedTarget ? null : previous.lastEvaluatedAt(), previous.generation());
        }
        Map<String, Item> updated = new LinkedHashMap<>(items);
        updated.put(id, next);
        commit(updated, lastCycleAt);
        // One pending pass includes games/targets changed after the current pass took its snapshot.
        // Refresh-button clicks do not set this flag or build a request queue.
        if (checking && needsCheck) refreshPending = true;
        return snapshot();
    }

    public synchronized String remove(String id) {
        ensureOpen();
        validId(id);
        Map<String, Item> updated = new LinkedHashMap<>(items);
        if (updated.remove(id) != null) commit(updated, lastCycleAt);
        return snapshot();
    }

    public synchronized String acknowledge(String id) {
        ensureOpen();
        validId(id);
        Item previous = items.get(id);
        if (previous != null && previous.notificationUnread()) {
            Map<String, Item> updated = new LinkedHashMap<>(items);
            updated.put(id, new Item(previous.id(), previous.title(), previous.steamAppId(), previous.targetPrice(),
                    previous.lastPrice(), previous.lastStore(), previous.lastCheckedAt(), previous.lastSuccessAt(),
                    previous.error(), previous.targetReached(), false, previous.lastEvaluatedAt(), previous.generation()));
            commit(updated, lastCycleAt);
        }
        return snapshot();
    }

    /** Returns false for an empty list or an already running check. Requests never queue extra refresh cycles. */
    public synchronized boolean requestRefresh() {
        if (closed || checking || items.isEmpty()) return false;
        checking = true;
        try { worker.execute(this::refreshCycle); }
        catch (RejectedExecutionException stopping) { checking = false; return false; }
        return true;
    }

    private void refreshCycle() {
        ArrayList<Item> selected;
        synchronized (this) {
            selected = new ArrayList<>(items.values());
            refreshPending = false;
        }
        try {
            for (Item selectedItem : selected) {
                synchronized (this) {
                    if (closed || Thread.currentThread().isInterrupted()) return;
                    Item current = items.get(selectedItem.id());
                    if (current == null || current.generation() != selectedItem.generation()) continue;
                }
                try {
                    Observation observation = parseObservation(selectedItem.id(), lookup.apply(selectedItem.id()));
                    applyObservation(selectedItem, observation);
                } catch (RuntimeException failure) {
                    applyFailure(selectedItem, safeFailure(failure));
                }
            }
        } finally {
            synchronized (this) {
                if (!closed) {
                    try { commit(new LinkedHashMap<>(items), clock.instant()); }
                    catch (IllegalStateException failure) { /* snapshot.storageError exposes the failed write. */ }
                }
                boolean followUp = !closed && refreshPending && !items.isEmpty();
                refreshPending = false;
                if (followUp) {
                    try { worker.execute(this::refreshCycle); }
                    catch (RejectedExecutionException stopping) { checking = false; }
                } else checking = false;
                notifyAll();
            }
        }
    }

    private synchronized void applyObservation(Item selected, Observation value) {
        Item previous = items.get(selected.id());
        if (closed || previous == null || previous.generation() != selected.generation()) return;
        Instant checkedAt = clock.instant();
        // A late or stale response must never replace a newer known observation.
        boolean newer = previous.lastSuccessAt() == null || !value.fetchedAt().isBefore(previous.lastSuccessAt());
        BigDecimal price = newer && value.price() != null ? value.price() : previous.lastPrice();
        String store = newer && value.price() != null ? value.store() : previous.lastStore();
        Instant succeededAt = newer && value.price() != null ? value.fetchedAt() : previous.lastSuccessAt();
        boolean reached = previous.targetReached();
        boolean unread = previous.notificationUnread();
        Instant evaluatedAt = previous.lastEvaluatedAt();
        String error = null;
        if (value.stale()) {
            error = "Prices could not be refreshed. Showing the last known price; no new alert was raised.";
        } else if (newer && (evaluatedAt == null || value.fetchedAt().isAfter(evaluatedAt))) {
            reached = value.price() != null && previous.targetPrice() != null
                    && value.price().compareTo(previous.targetPrice()) <= 0;
            unread = reached && (unread || !previous.targetReached());
            evaluatedAt = value.fetchedAt();
        }
        if (!value.stale() && value.price() == null) error = "No active store offers are available for this game.";
        Item next = new Item(previous.id(), value.title(), value.steamAppId(), previous.targetPrice(), price, store,
                checkedAt, succeededAt, error, reached, unread, evaluatedAt, previous.generation());
        replaceFromWorker(previous.id(), next);
    }

    private synchronized void applyFailure(Item selected, String message) {
        Item previous = items.get(selected.id());
        if (closed || previous == null || previous.generation() != selected.generation()) return;
        Item next = new Item(previous.id(), previous.title(), previous.steamAppId(), previous.targetPrice(),
                previous.lastPrice(), previous.lastStore(), clock.instant(), previous.lastSuccessAt(), message,
                previous.targetReached(), previous.notificationUnread(), previous.lastEvaluatedAt(), previous.generation());
        replaceFromWorker(previous.id(), next);
    }

    private void replaceFromWorker(String id, Item value) {
        Map<String, Item> updated = new LinkedHashMap<>(items);
        updated.put(id, value);
        try { commit(updated, lastCycleAt); }
        catch (IllegalStateException failure) { /* Keep the last successfully persisted in-memory state. */ }
    }

    private Observation parseObservation(String id, String response) {
        if (response == null || response.length() > 1024 * 1024) throw new IllegalArgumentException("Invalid game response.");
        JsonObject value = object(JSON.fromJson(response, JsonElement.class));
        if (!id.equals(string(value, "id", 20, false)) || !"USD".equals(string(value, "currency", 3, false)))
            throw new IllegalArgumentException("Unexpected game response.");
        String title = string(value, "title", 1000, false);
        String steam = string(value, "steamAppId", 20, true);
        if (steam != null && !steam.isEmpty() && !steam.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid Steam ID.");
        if (steam != null && steam.isEmpty()) steam = null;
        Instant fetched = time(value, "fetchedAt", false);
        if (fetched.isAfter(clock.instant().plus(Duration.ofMinutes(5)))) throw new IllegalArgumentException("Invalid price observation time.");
        boolean stale = bool(value, "stale");
        JsonElement rawOffers = value.get("offers");
        if (rawOffers == null || !rawOffers.isJsonArray() || rawOffers.getAsJsonArray().size() > 60)
            throw new IllegalArgumentException("Invalid game offers.");
        BigDecimal cheapest = null;
        String store = null;
        for (JsonElement raw : rawOffers.getAsJsonArray()) {
            JsonObject offer = object(raw);
            if (!bool(offer, "active")) continue;
            BigDecimal price = amount(offer, "price", false, 8);
            String name = string(offer, "storeName", 120, false);
            if (cheapest == null || price.compareTo(cheapest) < 0) { cheapest = price; store = name; }
        }
        return new Observation(title, steam, fetched, stale, cheapest, store);
    }

    private JsonObject snapshotObject() {
        JsonObject result = new JsonObject();
        result.addProperty("currency", "USD");
        result.addProperty("refreshIntervalSeconds", interval.toSeconds());
        result.addProperty("checking", checking);
        result.add("lastCycleAt", instant(lastCycleAt));
        result.addProperty("storageError", storageError);
        JsonArray values = new JsonArray();
        for (Item item : items.values()) values.add(itemJson(item, false));
        result.add("items", values);
        return result;
    }

    private JsonObject itemJson(Item item, boolean persisted) {
        JsonObject value = new JsonObject();
        value.addProperty("id", item.id());
        value.addProperty("title", item.title());
        value.addProperty("steamAppId", item.steamAppId());
        value.addProperty("targetPrice", item.targetPrice());
        value.addProperty("lastPrice", item.lastPrice());
        value.addProperty("lastStore", item.lastStore());
        value.add("lastCheckedAt", instant(item.lastCheckedAt()));
        value.add("lastSuccessAt", instant(item.lastSuccessAt()));
        value.addProperty("error", item.error());
        value.addProperty("targetReached", item.targetReached());
        value.addProperty("notificationUnread", item.notificationUnread());
        if (persisted) value.add("lastEvaluatedAt", instant(item.lastEvaluatedAt()));
        return value;
    }

    /** Publish the new state only after its complete file has replaced the previous file. */
    private void commit(Map<String, Item> updated, Instant cycleAt) {
        JsonObject state = new JsonObject();
        state.addProperty("schemaVersion", 1);
        state.addProperty("currency", "USD");
        state.add("lastCycleAt", instant(cycleAt));
        JsonArray values = new JsonArray();
        for (Item item : updated.values()) values.add(itemJson(item, true));
        state.add("items", values);
        Path temporary = null;
        try {
            byte[] bytes = JSON.toJson(state).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STATE_BYTES) throw new IOException("The saved watchlist exceeds its size limit.");
            temporary = Files.createTempFile(directory, "watchlist-", ".tmp");
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            try { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING); }
            items = updated;
            lastCycleAt = cycleAt;
            storageError = null;
        } catch (IOException failure) {
            storageError = "The watchlist could not be saved. Check the data folder and try again.";
            throw new IllegalStateException(storageError, failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private void load() throws IOException {
        if (!Files.exists(stateFile)) return;
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(stateFile)) { bytes = input.readNBytes(MAX_STATE_BYTES + 1); }
            if (bytes.length > MAX_STATE_BYTES) throw new IllegalArgumentException("The saved watchlist exceeds its size limit.");
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonObject state = object(JSON.fromJson(content, JsonElement.class));
            JsonElement version = state.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || !"1".equals(version.getAsString()) || !"USD".equals(string(state, "currency", 3, false)))
                throw new IllegalArgumentException("Unsupported watchlist format.");
            lastCycleAt = time(state, "lastCycleAt", true);
            JsonElement rawItems = state.get("items");
            if (rawItems == null || !rawItems.isJsonArray() || rawItems.getAsJsonArray().size() > MAX_ITEMS)
                throw new IllegalArgumentException("Invalid saved watchlist size.");
            for (JsonElement raw : rawItems.getAsJsonArray()) {
                JsonObject value = object(raw);
                String id = validId(string(value, "id", 20, false));
                String steam = string(value, "steamAppId", 20, true);
                if (steam != null && !steam.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid saved Steam ID.");
                BigDecimal target = validTarget(amount(value, "targetPrice", true, 2));
                BigDecimal price = amount(value, "lastPrice", true, 8);
                boolean reached = bool(value, "targetReached");
                boolean unread = bool(value, "notificationUnread");
                if ((reached && (target == null || price == null)) || (unread && !reached))
                    throw new IllegalArgumentException("Invalid saved alert state.");
                Item item = new Item(id, string(value, "title", 1000, false), steam, target, price,
                        string(value, "lastStore", 120, true), time(value, "lastCheckedAt", true),
                        time(value, "lastSuccessAt", true), string(value, "error", 300, true), reached, unread,
                        time(value, "lastEvaluatedAt", true), ++nextGeneration);
                if (items.put(id, item) != null) throw new IllegalArgumentException("Duplicate saved game ID.");
            }
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("The saved watchlist could not be read. Its file was preserved; fix or restore watchlist.json before starting.", invalid);
        }
    }

    private static String validId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Choose a valid game ID.");
        return id;
    }

    private static BigDecimal validTarget(BigDecimal price) {
        if (price == null) return null;
        if (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0 || price.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("Enter a USD target between 0 and 1,000,000 with at most two decimal places.");
        return price.setScale(2);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }

    private static String validText(String value, int maximum, String message) {
        if (value == null || value.isBlank() || value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(message);
        return value;
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected an object.");
        return value.getAsJsonObject();
    }

    private static String string(JsonObject value, String key, int maximum, boolean nullable) {
        JsonElement raw = value.get(key);
        if (nullable && raw != null && raw.isJsonNull()) return null;
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Invalid text field.");
        String text = raw.getAsString();
        // The provider uses an empty Steam ID when no Steam edition exists.
        if (nullable && key.equals("steamAppId") && text.isEmpty()) return "";
        return validText(text, maximum, "Invalid text field.");
    }

    private static Instant time(JsonObject value, String key, boolean nullable) {
        String text = string(value, key, 40, nullable);
        if (text == null) return null;
        Instant result = Instant.parse(text);
        if (result.isBefore(Instant.EPOCH)) throw new IllegalArgumentException("Invalid observation time.");
        return result;
    }

    private static boolean bool(JsonObject value, String key) {
        JsonElement raw = value.get(key);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Invalid boolean field.");
        return raw.getAsBoolean();
    }

    private static BigDecimal amount(JsonObject value, String key, boolean nullable, int decimals) {
        JsonElement raw = value.get(key);
        if (nullable && raw != null && raw.isJsonNull()) return null;
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid price.");
        String text = raw.getAsString();
        if (text.length() > 40) throw new IllegalArgumentException("Invalid price.");
        BigDecimal result = new BigDecimal(text);
        if (result.signum() < 0 || result.compareTo(MAX_PRICE) > 0 || result.stripTrailingZeros().scale() > decimals)
            throw new IllegalArgumentException("Invalid price.");
        return result;
    }

    private static String safeFailure(RuntimeException failure) {
        if (failure instanceof GamePriceService.ServiceException) {
            String message = failure.getMessage();
            if (message != null && !message.isBlank()) return message.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(300, message.length()));
        }
        return "This game's price could not be checked. Please try again later.";
    }

    private static JsonElement instant(Instant value) { return value == null ? JsonNull.INSTANCE : JSON.toJsonTree(value.toString()); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("The watchlist is closed."); }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            checking = false;
            refreshPending = false;
            notifyAll();
        }
        worker.shutdownNow();
        try { worker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { directoryLock.release(); } catch (IOException ignored) { }
        try { lockChannel.close(); } catch (IOException ignored) { }
    }

    private record Observation(String title, String steamAppId, Instant fetchedAt, boolean stale, BigDecimal price, String store) { }
    private record Item(String id, String title, String steamAppId, BigDecimal targetPrice, BigDecimal lastPrice,
                        String lastStore, Instant lastCheckedAt, Instant lastSuccessAt, String error,
                        boolean targetReached, boolean notificationUnread, Instant lastEvaluatedAt, long generation) { }
}
