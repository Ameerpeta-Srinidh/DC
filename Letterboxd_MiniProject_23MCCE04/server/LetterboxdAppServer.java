package server;

import data.DatabaseClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LetterboxdAppServer — the APP TIER. Accepts end-user client connections
 * exactly like v1's LetterboxdServer did, but holds NO database state of
 * its own: every request is handled by talking to a separate DatabaseServer
 * process over a DatabaseClient.
 *
 * Because this process is now STATELESS (no file, no in-memory data that
 * matters beyond one request), you can run as many of these as you want,
 * on as many different ports (or machines) as you want, ALL pointed at the
 * same DatabaseServer — that's horizontal scaling. Put LoadBalancer.java in
 * front of however many instances you start, and the client never needs to
 * know how many there are or which one it happened to land on.
 *
 * USAGE:
 *   java -cp ".;lib/*" server.LetterboxdAppServer [appPort] [dbHost] [dbPort]
 *   Defaults: 9876, localhost, 9800
 *
 * Run several of these on different ports (9876, 9877, 9878, ...) pointed
 * at the same DatabaseServer to see horizontal scaling in action.
 */
public class LetterboxdAppServer {

    private static final int DEFAULT_PORT = 9876;
    private static final int THREAD_POOL_SIZE = 20;

    public static void main(String[] args) {
        int appPort = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String dbHost = args.length > 1 ? args[1] : "localhost";
        int dbPort = args.length > 2 ? Integer.parseInt(args[2]) : 9800;

        DatabaseClient db = new DatabaseClient(dbHost, dbPort);
        RequestRouter router = new RequestRouter(db);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[AppServer:" + appPort + "] Shutting down...");
            pool.shutdownNow();
        }));

        try (ServerSocket serverSocket = new ServerSocket(appPort)) {
            System.out.println("================================================");
            System.out.println(" APP SERVER (stateless) running on port " + appPort);
            System.out.println(" -> talking to DatabaseServer at " + dbHost + ":" + dbPort);
            System.out.println("================================================");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.submit(new ConnectionHandler(clientSocket, router, "App:" + appPort));
            }
        } catch (IOException e) {
            System.err.println("[AppServer:" + appPort + "] Fatal: " + e.getMessage());
        }
    }
}
