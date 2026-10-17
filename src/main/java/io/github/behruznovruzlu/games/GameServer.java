package io.github.behruznovruzlu.games;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
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

/** Browser application and read-only price API, available only through IPv4 loopback. */
public final class GameServer implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final GamePriceService games;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Runnable shutdownAction = () -> {};

    public GameServer(int port) throws IOException {
        this(port, new GamePriceService());
    }

    /** Takes ownership of the client, including when binding the requested port fails. */
    GameServer(int port, GamePriceService games) throws IOException {
        this.games = java.util.Objects.requireNonNull(games, "games");
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
            throw error;
        }
    }

    public void start() { server.start(); }
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
                } else if (!method.equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", path.equals("/api/shutdown") ? "POST" : "GET");
                    send(exchange, 405, "application/json", "{\"error\":\"Unsupported method\"}");
                } else {
                    switch (path) {
                        case "/", "/games" -> resource(exchange, "/web/games.html", "text/html");
                        case "/games.js" -> resource(exchange, "/web/games.js", "text/javascript");
                        case "/games.css" -> resource(exchange, "/web/games.css", "text/css");
                        case "/api/games/search", "/api/games/game", "/api/games/health" -> gameRequest(exchange, path);
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

    private void gameRequest(HttpExchange exchange, String path) throws IOException {
        try {
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
        parameters(exchange.getRequestURI().getRawQuery(), List.of());
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
        games.close();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
