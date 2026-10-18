package io.github.behruznovruzlu.games;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browser application, read-only price lookups and local watches, available only through IPv4 loopback. */
public final class GameServer implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final GamePriceService games;
    private final WatchlistService watchlist;
    private final PriceWorkflowCheck workflow;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Runnable shutdownAction = () -> {};

    public GameServer(int port) throws IOException {
        this(port, new GamePriceService());
    }

    /** Takes ownership of the client, including when binding the requested port fails. */
    GameServer(int port, GamePriceService games) throws IOException {
        this(port, games, null);
    }

    GameServer(int port, GamePriceService games, WatchlistService watchlist) throws IOException {
        this(port, games, watchlist, null);
    }

    GameServer(int port, GamePriceService games, WatchlistService watchlist, PriceWorkflowCheck workflow) throws IOException {
        this.games = java.util.Objects.requireNonNull(games, "games");
        this.watchlist = watchlist;
        this.workflow = workflow;
        HttpServer created = null;
        ThreadPoolExecutor workers = null;
        try {
            if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be between 0 and 65535");
            created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
            workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            created.setExecutor(workers);
            created.createContext("/", this::handle);
            server = created;
            executor = workers;
        } catch (IOException | RuntimeException error) {
            if (created != null) created.stop(0);
            if (workers != null) workers.shutdownNow();
            games.close();
            if (watchlist != null) watchlist.close();
            if (workflow != null) workflow.close();
            throw error;
        }
    }

    public void start() {
        server.start();
        if (watchlist != null) watchlist.start();
        if (workflow != null) workflow.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public void onShutdown(Runnable action) { shutdownAction = java.util.Objects.requireNonNull(action); }

    private void handle(HttpExchange exchange) throws IOException {
        boolean shutdown = false;
        try (exchange) {
            securityHeaders(exchange);
            try {
                if (!singleHeaderMatches(exchange, "Host", "127.0.0.1:" + port(), false)
                        || !singleHeaderMatches(exchange, "Origin", "http://127.0.0.1:" + port(), true)) {
                    send(exchange, 403, "application/json", "{\"error\":\"Use this app's printed 127.0.0.1 URL\"}");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                // Resources are an explicit allowlist, never filesystem paths. Encoded route aliases are rejected too.
                if (!path.equals(exchange.getRequestURI().getRawPath())) {
                    send(exchange, 404, "application/json", "{\"error\":\"Not found\"}");
                    return;
                }
                String method = exchange.getRequestMethod();
                if (method.equals("POST") && path.equals("/api/shutdown")) {
                    if (!singleHeaderMatches(exchange, "X-App-Action", "change", false)) {
                        send(exchange, 403, "application/json", "{\"error\":\"Action header required\"}");
                        return;
                    }
                    parameters(exchange.getRequestURI().getRawQuery(), List.of());
                    send(exchange, 200, "application/json", "{\"stopped\":true}");
                    shutdown = true;
                } else if (method.equals("POST") && (path.equals("/api/watchlist") || path.startsWith("/api/watchlist/"))) {
                    watchlistRequest(exchange, path);
                } else if (!method.equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", path.equals("/api/shutdown") ? "POST" : "GET");
                    send(exchange, 405, "application/json", "{\"error\":\"Unsupported method\"}");
                } else {
                    switch (path) {
                        case "/", "/games" -> resource(exchange, "/web/games.html", "text/html", true);
                        case "/deals" -> resource(exchange, "/web/deals.html", "text/html");
                        case "/games.js" -> resource(exchange, "/web/games.js", "text/javascript");
                        case "/games.css" -> resource(exchange, "/web/games.css", "text/css");
                        case "/deals.js" -> resource(exchange, "/web/deals.js", "text/javascript");
                        case "/deals.css" -> resource(exchange, "/web/deals.css", "text/css");
                        case "/api/games/search", "/api/games/game", "/api/games/health", "/api/deals", "/api/stores" -> gameRequest(exchange, path);
                        case "/api/games/workflow" -> {
                            parameters(exchange.getRequestURI().getRawQuery(), List.of());
                            String body = workflow == null ? "{\"status\":\"disabled\",\"running\":false,\"query\":\"Portal\",\"error\":null}"
                                    : workflow.snapshot();
                            boolean healthy = JsonParser.parseString(body).getAsJsonObject().get("status").getAsString().equals("healthy");
                            send(exchange, healthy ? 200 : 503, "application/json", body);
                        }
                        case "/api/watchlist" -> {
                            parameters(exchange.getRequestURI().getRawQuery(), List.of());
                            if (watchlist == null) send(exchange, 503, "application/json", "{\"error\":\"Watchlist is unavailable\"}");
                            else send(exchange, 200, "application/json", watchlist.snapshot());
                        }
                        case "/api/games/readiness" -> {
                            parameters(exchange.getRequestURI().getRawQuery(), List.of());
                            send(exchange, 200, "application/json", "{\"status\":\"ready\"}");
                        }
                        default -> send(exchange, 404, "application/json", "{\"error\":\"Not found\"}");
                    }
                }
            } catch (IllegalArgumentException error) {
                send(exchange, 400, "application/json", "{\"error\":" + JSON.toJson(error.getMessage()) + "}");
            }
        } finally {
            if (shutdown) shutdownAction.run();
        }
    }

    private void watchlistRequest(HttpExchange exchange, String path) throws IOException {
        parameters(exchange.getRequestURI().getRawQuery(), List.of());
        if (!singleHeaderMatches(exchange, "X-App-Action", "change", false)) {
            send(exchange, 403, "application/json", "{\"error\":\"Action header required\"}");
            return;
        }
        if (watchlist == null) {
            send(exchange, 503, "application/json", "{\"error\":\"Watchlist is unavailable\"}");
            return;
        }
        try {
            JsonObject input = jsonBody(exchange);
            String body;
            switch (path) {
                case "/api/watchlist" -> {
                    requireKeys(input, List.of("id", "title", "targetPrice"));
                    BigDecimal target = null;
                    if (input.has("targetPrice") && !input.get("targetPrice").isJsonNull()) {
                        if (!input.get("targetPrice").isJsonPrimitive() || !input.getAsJsonPrimitive("targetPrice").isNumber())
                            throw new IllegalArgumentException("Target price must be a number or null");
                        target = input.get("targetPrice").getAsBigDecimal();
                    }
                    body = watchlist.upsert(stringValue(input, "id"), stringValue(input, "title"), target);
                }
                case "/api/watchlist/remove", "/api/watchlist/acknowledge" -> {
                    requireKeys(input, List.of("id"));
                    body = path.endsWith("/remove") ? watchlist.remove(stringValue(input, "id"))
                            : watchlist.acknowledge(stringValue(input, "id"));
                }
                case "/api/watchlist/refresh" -> {
                    requireKeys(input, List.of());
                    boolean accepted = watchlist.requestRefresh();
                    send(exchange, accepted ? 202 : 409, "application/json", "{\"checking\":true}");
                    return;
                }
                default -> { send(exchange, 404, "application/json", "{\"error\":\"Not found\"}"); return; }
            }
            send(exchange, 200, "application/json", body);
        } catch (IllegalStateException error) {
            send(exchange, 500, "application/json", "{\"error\":\"The watchlist could not be saved. Check the application data directory.\"}");
        }
    }

    private static JsonObject jsonBody(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
            throw new IllegalArgumentException("Use an application/json body");
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new IllegalArgumentException("Request body is too large");
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            var value = JsonParser.parseReader(reader);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Use a JSON object");
            return value.getAsJsonObject();
        } catch (IOException | com.google.gson.JsonParseException error) {
            throw new IllegalArgumentException("Use a valid JSON object");
        }
    }

    private static void requireKeys(JsonObject value, List<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw new IllegalArgumentException("Unknown request field");
    }

    private static String stringValue(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException("Missing text field: " + key);
        return value.get(key).getAsString();
    }

    private void gameRequest(HttpExchange exchange, String path) throws IOException {
        try {
            if (path.equals("/api/deals")) {
                var input = parameters(exchange.getRequestURI().getRawQuery(), List.of("maxPrice", "storeId", "sort", "page"));
                String price = input.getOrDefault("maxPrice", "");
                if (!price.isEmpty() && !price.matches("[0-9]{1,7}(?:\\.[0-9]{1,2})?"))
                    throw new IllegalArgumentException("Maximum price must be a USD amount with at most two decimal places");
                String page = input.getOrDefault("page", "0");
                if (!page.matches("[0-9]{1,4}")) throw new IllegalArgumentException("Page must be between 0 and 1000");
                String body = games.deals(price.isEmpty() ? null : new BigDecimal(price), input.get("storeId"),
                        input.getOrDefault("sort", "rating"), Integer.parseInt(page));
                send(exchange, 200, "application/json", body);
                return;
            }
            if (path.equals("/api/stores")) {
                parameters(exchange.getRequestURI().getRawQuery(), List.of());
                send(exchange, 200, "application/json", games.stores());
                return;
            }
            if (path.equals("/api/games/health")) {
                parameters(exchange.getRequestURI().getRawQuery(), List.of());
                String body = games.health();
                boolean degraded = JsonParser.parseString(body).getAsJsonObject().get("status").getAsString().equals("degraded");
                send(exchange, degraded ? 503 : 200, "application/json", body);
                return;
            }
            String key = path.endsWith("/search") ? "q" : "id";
            var input = parameters(exchange.getRequestURI().getRawQuery(), List.of(key));
            String body = key.equals("q") ? games.search(input.getOrDefault(key, "")) : games.game(input.getOrDefault(key, ""));
            send(exchange, 200, "application/json", body);
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "application/json", "{\"error\":" + JSON.toJson(error.getMessage()) + "}");
        } catch (GamePriceService.ServiceException error) {
            if (error.retryAfterSeconds() > 0) exchange.getResponseHeaders().set("Retry-After", Long.toString(error.retryAfterSeconds()));
            send(exchange, error.status(), "application/json", "{\"error\":" + JSON.toJson(error.getMessage())
                    + ",\"retryAfterSeconds\":" + error.retryAfterSeconds() + "}");
        }
    }

    private static boolean singleHeaderMatches(HttpExchange exchange, String name, String expected, boolean optional) {
        var values = exchange.getRequestHeaders().get(name);
        return values == null ? optional : values.size() == 1 && values.getFirst().equals(expected);
    }

    private static Map<String, String> parameters(String raw, List<String> allowed) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        if (raw.length() > 4096) throw new IllegalArgumentException("Request query is too long");
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown query parameter");
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate query parameter");
        }
        return values;
    }

    private static void resource(HttpExchange exchange, String resource, String type) throws IOException {
        resource(exchange, resource, type, false);
    }

    private static void resource(HttpExchange exchange, String resource, String type, boolean gameLink) throws IOException {
        var query = parameters(exchange.getRequestURI().getRawQuery(), gameLink ? List.of("game") : List.of());
        if (query.containsKey("game") && !query.get("game").matches("[1-9][0-9]{0,19}"))
            throw new IllegalArgumentException("Game ID must be a positive numeric identifier");
        try (var input = GameServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                send(exchange, 500, "application/json", "{\"error\":\"Missing application resource\"}");
                return;
            }
            send(exchange, 200, type, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
        if (workflow != null) workflow.close();
        if (watchlist != null) watchlist.close();
        games.close();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
