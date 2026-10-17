package io.github.behruznovruzlu.games;

import java.awt.Desktop;
import java.net.BindException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() { }

    record Options(int port, boolean portSpecified, boolean openBrowser, boolean help) { }

    public static void main(String[] args) {
        boolean openBrowser = List.of(args).contains("--open-browser");
        try {
            Options options = parseOptions(args);
            if (options.help()) { help(); return; }
            CountDownLatch stop = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                stop.countDown();
                try { finished.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, "game-price-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try (GameServer active = createServer(options)) {
                active.onShutdown(stop::countDown);
                active.start();
                URI address = URI.create("http://127.0.0.1:" + active.port());
                System.out.println("Game Price Search: " + address);
                System.out.println("Compare PC game store prices supplied in USD by CheapShark. Internet access is required.");
                if (options.openBrowser()) browse(address);
                stop.await();
            } finally {
                finished.countDown();
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException error) { /* JVM shutdown is already in progress. */ }
            }
        } catch (Exception error) {
            System.err.println("Cannot start: " + error.getMessage());
            if (openBrowser && !java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null, "Game Price Search could not start.\n" + error.getMessage(),
                        "Game Price Search", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
        }
    }

    static Options parseOptions(String[] args) {
        int port = 8090;
        boolean portSpecified = false;
        boolean openBrowser = false;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help" -> help = true;
                case "--open-browser" -> {
                    if (openBrowser) throw new IllegalArgumentException("Duplicate --open-browser option");
                    openBrowser = true;
                }
                case "--port" -> {
                    if (portSpecified) throw new IllegalArgumentException("Duplicate --port option");
                    if (++i >= args.length) throw new IllegalArgumentException("Missing value for --port");
                    try { port = Integer.parseInt(args[i]); }
                    catch (NumberFormatException error) { throw new IllegalArgumentException("--port must be an integer between 0 and 65535"); }
                    if (port < 0 || port > 65_535) throw new IllegalArgumentException("--port must be between 0 and 65535");
                    portSpecified = true;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        return new Options(port, portSpecified, openBrowser, help);
    }

    static GameServer createServer(Options options) throws java.io.IOException {
        try { return new GameServer(options.port()); }
        catch (BindException error) {
            if (options.portSpecified()) throw error;
            return new GameServer(0);
        }
    }

    private static void browse(URI address) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(address);
                return;
            }
        } catch (Exception error) { /* The printed local URL remains usable. */ }
        System.err.println("Open " + address + " in your browser.");
    }

    private static void help() {
        System.out.println("""
                Game Price Search (Java 21)
                java -jar target/game-price-search-1.0.0.jar [options]
                  --open-browser    Open the local application in the default browser
                  --port PORT       Local web port; default: 8090, or a free port if it is occupied
                                    Use 0 to request a free port explicitly
                  --help            Show this help
                Game prices are supplied in USD by the online CheapShark API.
                Saved games stay in this browser's local storage, tied to the local URL.
                """);
    }
}
