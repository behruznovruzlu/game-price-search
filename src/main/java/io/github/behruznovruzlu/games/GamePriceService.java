package io.github.behruznovruzlu.games;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** A bounded, read-only CheapShark client. Prices are supplied in USD by CheapShark. */
public final class GamePriceService implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create();
    private static final int MAX_BODY = 1024 * 1024;
    private static final int CACHE_CAPACITY = 128;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
    private static final Pattern GTA_FIVE_ALIAS = Pattern.compile("(?:gta|grand theft auto) ?(?:5|v)", Pattern.CASE_INSENSITIVE);
    private final URI base;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration requestInterval;
    private final HttpClient client;
    private final ReentrantLock requestLock = new ReentrantLock();
    private final Map<String, CacheEntry> searches = lruCache();
    private final Map<String, CacheEntry> games = lruCache();
    private final Map<String, CacheEntry> dealPages = lruCache();
    private Map<String, Store> stores;
    private Instant storesFetchedAt;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;
    private volatile String lastError;
    private volatile Instant cooldownUntil = Instant.MIN;
    private long lastRequestNanos;
    private volatile long upstreamRequests;
    private volatile long cacheHits;
    private volatile long staleResponses;
    private volatile long upstreamFailures;
    private volatile int cachedSearches;
    private volatile int cachedGames;
    private volatile int cachedDealPages;
    private volatile boolean lastAttemptFailed;
    private volatile boolean closed;

    public GamePriceService() {
        this(URI.create("https://www.cheapshark.com/api/1.0/"), Clock.systemUTC(),
                Duration.ofMinutes(15), Duration.ofSeconds(1));
    }

    // Only tests supply a different origin. Requests never follow redirects or accept a user-supplied URL.
    GamePriceService(URI base, Clock clock, Duration cacheTtl, Duration requestInterval) {
        if (base == null || base.getHost() == null || base.getRawQuery() != null || base.getFragment() != null
                || base.getUserInfo() != null || !base.toString().endsWith("/")
                || !(base.getScheme().equals("https") || base.getScheme().equals("http")))
            throw new IllegalArgumentException("A fixed HTTP API base ending in / is required.");
        if (cacheTtl.isNegative() || cacheTtl.isZero() || requestInterval.isNegative())
            throw new IllegalArgumentException("Invalid cache or request interval.");
        this.base = base;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.cacheTtl = cacheTtl;
        this.requestInterval = requestInterval;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
        if (usesWindowsTrust(base)) builder.sslContext(WindowsTrustHolder.VALUE.contextOrThrow());
        this.client = builder.build();
    }

    private static boolean usesWindowsTrust(URI base) {
        return usesWindowsTrust(base, System.getProperty("os.name", ""),
                System.getProperty("javax.net.ssl.trustStore"), System.getProperty("javax.net.ssl.trustStoreType"));
    }

    static boolean usesWindowsTrust(URI base, String osName, String trustStore, String trustStoreType) {
        return base.getScheme().equals("https") && osName.startsWith("Windows")
                && trustStore == null && trustStoreType == null;
    }

    /** Loads native roots once, scoped to this client; normal TLS hostname checks still apply. */
    private static final class WindowsTrustHolder {
        private static final WindowsTrust VALUE = load();
        private static WindowsTrust load() {
            try {
                KeyStore roots = KeyStore.getInstance("Windows-ROOT");
                roots.load(null, null);
                TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(roots);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trust.getTrustManagers(), null);
                return new WindowsTrust(context, null);
            } catch (GeneralSecurityException | java.io.IOException | RuntimeException error) {
                return new WindowsTrust(null, error);
            }
        }
    }

    private record WindowsTrust(SSLContext context, Exception error) {
        SSLContext contextOrThrow() {
            if (error != null) throw new IllegalStateException(
                    "Windows trusted certificates could not be loaded. The Java runtime must include jdk.crypto.mscapi for verified HTTPS.", error);
            return context;
        }
    }

    /** Bounded serial access prevents a lookup burst from occupying every local HTTP worker. */
    public String search(String query) {
        return search(query, false);
    }

    /** The opt-in workflow check must observe the provider, not a previous cached success. */
    String freshSearch(String query) {
        return search(query, true);
    }

    private String search(String query, boolean fresh) {
        ensureOpen();
        if (query == null) throw invalid("Enter a game title.");
        String normalized = query.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 120
                || normalized.codePoints().anyMatch(Character::isISOControl))
            throw invalid("Enter a game title between 1 and 120 characters.");
        String resolved = GTA_FIVE_ALIAS.matcher(normalized).matches() ? "Grand Theft Auto V" : normalized;
        return locked(() -> cached(searches, normalized, fresh, () -> {
            JsonArray upstream = array(fetch("games?title=" + encode(resolved) + "&limit=30&exact=0"));
            if (upstream.size() > 60) throw malformed();
            JsonArray matches = new JsonArray();
            for (JsonElement element : upstream) {
                JsonObject item = object(element);
                JsonObject match = new JsonObject();
                match.addProperty("id", identifier(item, "gameID"));
                match.addProperty("title", title(item, "external"));
                match.addProperty("steamAppId", optionalIdentifier(item, "steamAppID"));
                match.addProperty("cheapestPrice", amount(item, "cheapest", MAX_PRICE));
                match.addProperty("dealUrl", dealUrl(text(item, "cheapestDealID", 2048)));
                matches.add(match);
            }
            JsonObject result = new JsonObject();
            result.addProperty("query", normalized);
            if (!resolved.equals(normalized)) result.addProperty("resolvedQuery", resolved);
            result.add("games", matches);
            return result;
        }));
    }

    public String game(String id) {
        return game(id, false);
    }

    String freshGame(String id) {
        return game(id, true);
    }

    private String game(String id, boolean fresh) {
        ensureOpen();
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) throw invalid("Choose a valid game ID.");
        return locked(() -> cached(games, id, fresh, () -> {
            Map<String, Store> metadata = loadStores();
            JsonObject upstream = object(fetch("games?id=" + id));
            JsonObject info = object(required(upstream, "info"));
            JsonArray deals = array(required(upstream, "deals"));
            if (deals.size() > 500) throw malformed();
            List<Offer> offers = new ArrayList<>();
            for (JsonElement element : deals) {
                JsonObject item = object(element);
                String storeId = identifier(item, "storeID");
                Store store = metadata.get(storeId);
                if (store == null) throw malformed();
                // Stores can remain in historical API records after becoming inactive.
                if (!store.active()) continue;
                offers.add(new Offer(storeId, store.name(), amount(item, "price", MAX_PRICE),
                        amount(item, "retailPrice", MAX_PRICE), amount(item, "savings", new BigDecimal("100")),
                        dealUrl(text(item, "dealID", 2048))));
            }
            offers.sort(Comparator.comparing(Offer::price).thenComparing(Offer::storeId).thenComparing(Offer::url));
            JsonArray resultOffers = new JsonArray();
            for (Offer offer : offers.subList(0, Math.min(60, offers.size()))) {
                JsonObject value = new JsonObject();
                value.addProperty("storeId", offer.storeId());
                value.addProperty("storeName", offer.storeName());
                value.addProperty("active", true);
                value.addProperty("price", offer.price());
                value.addProperty("retailPrice", offer.retailPrice());
                value.addProperty("savings", offer.savings());
                value.addProperty("dealUrl", offer.url());
                resultOffers.add(value);
            }
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("title", title(info, "title"));
            result.addProperty("steamAppId", optionalIdentifier(info, "steamAppID"));
            result.add("lowestEver", lowestEver(upstream.get("cheapestPriceEver")));
            result.add("offers", resultOffers);
            return result;
        }));
    }

    /** Fetches one provider page only. Filtering a page can leave fewer than 30 visible deals. */
    public String deals(BigDecimal maxPrice, String storeId, String sort, int page) {
        ensureOpen();
        if (maxPrice != null && (maxPrice.signum() < 0 || maxPrice.compareTo(MAX_PRICE) > 0
                || maxPrice.stripTrailingZeros().scale() > 2))
            throw invalid("Choose a maximum USD price from 0 to 1000000 with at most two decimal places.");
        String selectedStore = storeId == null || storeId.isEmpty() || storeId.equals("all") ? "" : storeId;
        if (!selectedStore.isEmpty() && !selectedStore.matches("[1-9][0-9]{0,19}"))
            throw invalid("Choose a valid store ID.");
        String order = sort == null || sort.isEmpty() ? "rating" : sort;
        String ordering = switch (order) {
            case "rating" -> "sortBy=DealRating&desc=0";
            case "discount" -> "sortBy=Savings&desc=0";
            case "price" -> "sortBy=Price&desc=0";
            case "recent" -> "sortBy=Recent&desc=0";
            default -> throw invalid("Choose a valid deal sort order.");
        };
        if (page < 0 || page > 1000) throw invalid("Choose a deal page from 0 to 1000.");
        String canonicalPrice = maxPrice == null ? "" : maxPrice.stripTrailingZeros().toPlainString();
        String key = canonicalPrice + ":" + selectedStore + ":" + order + ":" + page;
        StringBuilder path = new StringBuilder("deals?pageNumber=").append(page)
                .append("&pageSize=30&onSale=1&").append(ordering);
        if (!selectedStore.isEmpty()) path.append("&storeID=").append(selectedStore);
        if (maxPrice != null) {
            // CheapShark treats upperPrice=50 as unlimited. The local exact-price check is authoritative.
            BigDecimal ceiling = maxPrice.setScale(0, RoundingMode.CEILING);
            if (ceiling.compareTo(new BigDecimal("50")) != 0) path.append("&upperPrice=").append(ceiling.toPlainString());
        }
        return locked(() -> cached(dealPages, key, false, () -> {
            Map<String, Store> metadata = loadStores();
            JsonArray upstream = array(fetch(path.toString()));
            if (upstream.size() > 30) throw malformed();
            JsonArray matches = new JsonArray();
            for (JsonElement element : upstream) {
                JsonObject item = object(element);
                String itemStoreId = identifier(item, "storeID");
                Store store = metadata.get(itemStoreId);
                if (store == null) throw malformed();
                BigDecimal onSale = amount(item, "isOnSale", BigDecimal.ONE);
                if (!(onSale.compareTo(BigDecimal.ZERO) == 0 || onSale.compareTo(BigDecimal.ONE) == 0)) throw malformed();
                if (!store.active() || onSale.signum() == 0 || !selectedStore.isEmpty() && !selectedStore.equals(itemStoreId)) continue;
                BigDecimal sale = amount(item, "salePrice", MAX_PRICE);
                BigDecimal normal = amount(item, "normalPrice", MAX_PRICE);
                if (sale.compareTo(normal) >= 0 || maxPrice != null && sale.compareTo(maxPrice) > 0) continue;
                BigDecimal savings = amount(item, "savings", new BigDecimal("100"));
                if (savings.signum() == 0) continue;
                JsonObject deal = new JsonObject();
                deal.addProperty("id", identifier(item, "gameID"));
                deal.addProperty("title", title(item, "title"));
                deal.addProperty("steamAppId", optionalIdentifier(item, "steamAppID"));
                deal.addProperty("storeId", itemStoreId);
                deal.addProperty("storeName", store.name());
                deal.addProperty("salePrice", sale);
                deal.addProperty("normalPrice", normal);
                deal.addProperty("savings", savings);
                deal.addProperty("dealRating", amount(item, "dealRating", BigDecimal.TEN));
                deal.addProperty("dealUrl", dealUrl(text(item, "dealID", 2048)));
                matches.add(deal);
            }
            JsonObject result = new JsonObject();
            result.addProperty("page", page);
            result.addProperty("pageSize", 30);
            // This is a continuation hint, not a provider total or a promise of another matching deal.
            result.addProperty("hasMore", upstream.size() == 30);
            result.add("deals", matches);
            return result;
        }));
    }

    /** Active store choices share the same bounded, validated metadata used for game offers. */
    public String stores() {
        ensureOpen();
        return locked(() -> {
            boolean hit = stores != null && clock.instant().isBefore(storesFetchedAt.plus(Duration.ofHours(24)));
            try {
                Map<String, Store> metadata = loadStores();
                if (hit) cacheHits++;
                else {
                    lastSuccessAt = storesFetchedAt;
                    lastAttemptFailed = false;
                    lastError = null;
                }
                return response(activeStores(metadata), hit, false, "");
            } catch (ServiceException error) {
                if (stores == null) throw error;
                staleResponses++;
                return response(activeStores(stores), true, true,
                        error.getMessage() + " Showing previously fetched store choices.");
            }
        });
    }

    private CacheEntry activeStores(Map<String, Store> metadata) {
        JsonArray values = new JsonArray();
        metadata.entrySet().stream().filter(entry -> entry.getValue().active())
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(BigInteger::new)))
                .forEach(entry -> {
                    JsonObject store = new JsonObject();
                    store.addProperty("id", entry.getKey());
                    store.addProperty("name", entry.getValue().name());
                    values.add(store);
                });
        JsonObject result = new JsonObject();
        result.add("stores", values);
        return new CacheEntry(result, storesFetchedAt);
    }

    /** Reports local observations; it never probes or spends the upstream rate limit. */
    public String health() {
        JsonObject value = new JsonObject();
        value.addProperty("status", closed || lastAttemptFailed ? "degraded" : lastSuccessAt == null ? "ready" : "healthy");
        value.addProperty("source", "CheapShark");
        value.add("lastSuccessAt", instant(lastSuccessAt));
        value.add("lastFailureAt", instant(lastFailureAt));
        value.add("lastError", lastError == null ? JsonNull.INSTANCE : JSON.toJsonTree(lastError));
        value.addProperty("upstreamRequests", upstreamRequests);
        value.addProperty("upstreamFailures", upstreamFailures);
        value.addProperty("cacheHits", cacheHits);
        value.addProperty("staleResponses", staleResponses);
        value.addProperty("cachedSearches", cachedSearches);
        value.addProperty("cachedGames", cachedGames);
        value.addProperty("cachedDealPages", cachedDealPages);
        value.addProperty("retryAfterSeconds", remainingCooldown());
        return JSON.toJson(value);
    }

    private String cached(Map<String, CacheEntry> cache, String key, boolean fresh, Loader loader) {
        CacheEntry entry = cache.get(key);
        if (!fresh && entry != null && clock.instant().isBefore(entry.at().plus(cacheTtl))) {
            cacheHits++;
            return response(entry, true, false, "");
        }
        try {
            requireNotCoolingDown();
            JsonObject result = loader.load();
            CacheEntry loaded = new CacheEntry(result, clock.instant());
            cache.put(key, loaded);
            cachedSearches = searches.size();
            cachedGames = games.size();
            cachedDealPages = dealPages.size();
            lastSuccessAt = loaded.at();
            lastAttemptFailed = false;
            lastError = null;
            return response(loaded, false, false, "");
        } catch (ServiceException error) {
            if (!fresh && entry != null) {
                staleResponses++;
                return response(entry, true, true, error.getMessage() + " Showing previously fetched prices; verify the current price at the store.");
            }
            throw error;
        }
    }

    private String locked(Supplier<String> lookup) {
        try {
            if (!requestLock.tryLock(150, TimeUnit.MILLISECONDS))
                throw new ServiceException(503, "Another price lookup is in progress. Please retry shortly.", 1, true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceException(503, "The price request was interrupted. Please try again.", 1);
        }
        try {
            ensureOpen();
            return lookup.get();
        } finally { requestLock.unlock(); }
    }

    private String response(CacheEntry entry, boolean cached, boolean stale, String warning) {
        JsonObject result = entry.value().deepCopy();
        result.addProperty("currency", "USD");
        result.addProperty("fetchedAt", entry.at().toString());
        result.addProperty("cached", cached);
        result.addProperty("stale", stale);
        result.addProperty("warning", warning);
        return JSON.toJson(result);
    }

    private Map<String, Store> loadStores() {
        if (stores != null && clock.instant().isBefore(storesFetchedAt.plus(Duration.ofHours(24)))) return stores;
        JsonArray values = array(fetch("stores"));
        if (values.isEmpty() || values.size() > 200) throw malformed();
        Map<String, Store> parsed = new LinkedHashMap<>();
        for (JsonElement element : values) {
            JsonObject item = object(element);
            String id = identifier(item, "storeID");
            String name = text(item, "storeName", 120);
            BigDecimal active = amount(item, "isActive", BigDecimal.ONE);
            if (!(active.compareTo(BigDecimal.ZERO) == 0 || active.compareTo(BigDecimal.ONE) == 0)
                    || parsed.put(id, new Store(name, active.compareTo(BigDecimal.ONE) == 0)) != null) throw malformed();
        }
        stores = Map.copyOf(parsed);
        storesFetchedAt = clock.instant();
        return stores;
    }

    private JsonElement fetch(String path) {
        requireNotCoolingDown();
        long waitNanos = requestInterval.toNanos() - (System.nanoTime() - lastRequestNanos);
        if (lastRequestNanos != 0 && waitNanos > 0) {
            try { TimeUnit.NANOSECONDS.sleep(waitNanos); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure(503, "The price request was interrupted. Please try again.", 1);
            }
        }
        lastRequestNanos = System.nanoTime();
        upstreamRequests++;
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "GamePriceSearch/1.0 (local desktop application)").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            int code = response.statusCode();
            if (code == 429) throw failure(503, "CheapShark is limiting requests. Please wait before trying again.", retryAfter(response));
            if (code < 200 || code >= 300) throw failure(502, "CheapShark could not complete the price request. Please try again later.", 5);
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            JsonElement parsed = JSON.fromJson(body, JsonElement.class);
            if (parsed == null || parsed.isJsonNull()) throw malformed();
            return parsed;
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw failure(504, "CheapShark took too long to respond. Please try again later.", 5);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(503, "The price request was interrupted. Please try again.", 1);
        } catch (ExecutionException transport) {
            Throwable cause = transport.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException)
                throw failure(504, "CheapShark took too long to respond. Please try again later.", 5);
            throw failure(502, "Could not reach CheapShark. Check your connection and try again later.", 5);
        } catch (CharacterCodingException | com.google.gson.JsonParseException invalidJson) {
            throw malformed();
        }
    }

    private long retryAfter(HttpResponse<?> response) {
        String header = response.headers().firstValue("Retry-After").orElse("60").strip();
        long maximum = Duration.between(clock.instant(), Instant.MAX.minusSeconds(1)).getSeconds();
        if (header.matches("[0-9]+")) {
            // Never shorten the provider's pause, even if it exceeds a signed long.
            if (header.length() > 20) return maximum;
            return new BigInteger(header).max(BigInteger.ONE).min(BigInteger.valueOf(maximum)).longValue();
        }
        try {
            return Math.max(1, Duration.between(clock.instant(),
                    ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());
        } catch (RuntimeException invalidDate) { return 60; }
    }

    private ServiceException malformed() {
        return failure(502, "CheapShark returned incomplete or invalid price data. Please try again later.", 5);
    }

    private ServiceException failure(int status, String message, long delay) {
        lastFailureAt = clock.instant();
        lastAttemptFailed = true;
        lastError = message;
        upstreamFailures++;
        Instant until = clock.instant().plusSeconds(delay);
        if (until.isAfter(cooldownUntil)) cooldownUntil = until;
        return new ServiceException(status, message, delay);
    }

    private void requireNotCoolingDown() {
        long remaining = remainingCooldown();
        if (remaining > 0) throw new ServiceException(503,
                lastError == null ? "Please wait before requesting more prices." : lastError, remaining);
    }

    private long remainingCooldown() {
        Duration remaining = Duration.between(clock.instant(), cooldownUntil);
        return remaining.isNegative() || remaining.isZero() ? 0 : Math.max(1, remaining.toSeconds() + (remaining.getNano() > 0 ? 1 : 0));
    }

    private JsonElement lowestEver(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return JsonNull.INSTANCE;
        JsonObject item = object(raw);
        JsonObject result = new JsonObject();
        result.addProperty("price", amount(item, "price", MAX_PRICE));
        BigDecimal seconds = amount(item, "date", new BigDecimal("253402300799"));
        try { result.addProperty("at", Instant.ofEpochSecond(seconds.longValueExact()).toString()); }
        catch (ArithmeticException invalid) { throw malformed(); }
        return result;
    }

    private JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw malformed();
        return element.getAsJsonObject();
    }

    private JsonArray array(JsonElement element) {
        if (element == null || !element.isJsonArray()) throw malformed();
        return element.getAsJsonArray();
    }

    private JsonElement required(JsonObject value, String key) {
        JsonElement element = value.get(key);
        if (element == null || element.isJsonNull()) throw malformed();
        return element;
    }

    private String text(JsonObject value, String key, int max) {
        JsonElement element = required(value, key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw malformed();
        String result = element.getAsString();
        if (result.isBlank() || result.length() > max || result.codePoints().anyMatch(Character::isISOControl)) throw malformed();
        return result;
    }

    private String title(JsonObject value, String key) {
        return text(value, key, 1000);
    }

    private String identifier(JsonObject value, String key) {
        String result = text(value, key, 20);
        if (!result.matches("[1-9][0-9]{0,19}")) throw malformed();
        return result;
    }

    private String optionalIdentifier(JsonObject value, String key) {
        JsonElement element = value.get(key);
        if (element == null || element.isJsonNull()) return "";
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw malformed();
        String result = element.getAsString();
        if (!result.isEmpty() && !result.matches("[0-9]{1,20}")) throw malformed();
        return result;
    }

    private BigDecimal amount(JsonObject value, String key, BigDecimal maximum) {
        JsonElement element = required(value, key);
        if (!element.isJsonPrimitive() || !(element.getAsJsonPrimitive().isNumber() || element.getAsJsonPrimitive().isString())) throw malformed();
        String raw = element.getAsString();
        if (!raw.matches("[0-9]{1,15}(\\.[0-9]{1,8})?")) throw malformed();
        try {
            BigDecimal result = new BigDecimal(raw);
            if (result.compareTo(maximum) > 0) throw malformed();
            return result;
        } catch (NumberFormatException invalid) { throw malformed(); }
    }

    private String dealUrl(String raw) {
        try {
            // The API already percent-encodes deal IDs. Decode once, then encode exactly once for our fixed URL.
            String decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (!decoded.matches("[A-Za-z0-9+/=_-]{1,1024}")) throw malformed();
            return "https://www.cheapshark.com/redirect?dealID=" + encode(decoded);
        } catch (IllegalArgumentException invalid) { throw malformed(); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static JsonElement instant(Instant value) { return value == null ? JsonNull.INSTANCE : JSON.toJsonTree(value.toString()); }
    private static ServiceException invalid(String message) { return new ServiceException(400, message, 0); }
    private void ensureOpen() { if (closed) throw new ServiceException(503, "The price service is closed.", 0); }

    private static Map<String, CacheEntry> lruCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) { return size() > CACHE_CAPACITY; }
        };
    }

    @Override public void close() {
        closed = true;
        client.shutdownNow();
    }

    public static final class ServiceException extends RuntimeException {
        private final int status;
        private final long retryAfterSeconds;
        private final boolean busy;
        public ServiceException(int status, String message, long retryAfterSeconds) {
            this(status, message, retryAfterSeconds, false);
        }
        ServiceException(int status, String message, long retryAfterSeconds, boolean busy) {
            super(message);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
            this.busy = busy;
        }
        public int status() { return status; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
        public boolean isBusy() { return busy; }
    }

    private record Store(String name, boolean active) { }
    private record Offer(String storeId, String storeName, BigDecimal price, BigDecimal retailPrice, BigDecimal savings, String url) { }
    private record CacheEntry(JsonObject value, Instant at) { }
    @FunctionalInterface private interface Loader { JsonObject load(); }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalArgumentException("Upstream response exceeds limit."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
