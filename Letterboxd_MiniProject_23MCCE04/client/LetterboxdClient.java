package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.Socket;
import java.time.LocalDate;
import java.util.Scanner;

/**
 * LetterboxdClient — the interactive console client.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * HOW THIS CLIENT WORKS:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. Connects to the server via a TCP Socket (like socket()+connect() in C)
 * 2. Shows an interactive menu (Register / Login / Exit)
 * 3. On each user action:
 *    a. Builds a JSON request (e.g., {"type":"LOGIN","username":"srinidh",...})
 *    b. Sends it as a single line over the socket
 *    c. Reads the server's JSON response line
 *    d. Parses and pretty-prints the response
 *
 * MULTIPLE CLIENTS:
 *   You can open multiple terminal windows and run this client in each one.
 *   Each client gets its own Socket connection to the server, handled by
 *   its own thread on the server side. This demonstrates concurrent access.
 *
 * COMPARISON TO REAL LETTERBOXD:
 *   Our console client     →    Real Letterboxd
 *   ────────────────────         ──────────────────────────────────
 *   Scanner (user input)        Mobile app UI / Web browser
 *   Socket + PrintWriter        HTTPS connection to API
 *   JSON request strings        REST API calls (POST /api/v1/log)
 *   readLine() response         HTTP response with JSON body
 *
 * USAGE:
 *   java -cp ".;lib/*" client.LetterboxdClient [host] [port]
 *   Default: localhost 9876
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class LetterboxdClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 9876;

    // Socket I/O — shared across the entire session
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner scanner;

    // Session state — tracks who is logged in AND the server-issued token proving it.
    // Every request that acts "as" this user must carry sessionToken — the server no
    // longer trusts a bare username field (see SessionManager's class comment).
    private String loggedInUser = null;
    private String sessionToken = null;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        if (args.length >= 1) host = args[0];
        if (args.length >= 2) {
            try { port = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { /* use default */ }
        }

        new LetterboxdClient().start(host, port);
    }

    /**
     * Connect to the server and enter the main interaction loop.
     */
    public void start(String host, int port) {
        scanner = new Scanner(System.in);

        try {
            // ─── Connect to the server ──────────────────────────────
            // This is equivalent to: socket() + connect() in C
            // Java does both in a single constructor call.
            System.out.println("Connecting to " + host + ":" + port + "...");
            socket = new Socket(host, port);

            // Set up line-based I/O (matching the server's protocol)
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            System.out.println("Connected!\n");

            // ─── Main interaction loop ──────────────────────────────
            boolean running = true;
            while (running) {
                if (loggedInUser == null) {
                    running = showPreLoginMenu();
                } else {
                    running = showMainMenu();
                }
            }

        } catch (IOException e) {
            System.err.println("Could not connect to server: " + e.getMessage());
            System.err.println("Make sure the server is running on " + host + ":" + port);
        } finally {
            cleanup();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MENUS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Pre-login menu — shown before the user has authenticated.
     */
    private boolean showPreLoginMenu() {
        printHeader("LETTERBOXD — Film Diary");
        System.out.println("  1. Register");
        System.out.println("  2. Login");
        System.out.println("  3. Exit");
        printDivider();

        int choice = readInt("Choice: ", 1, 3);

        switch (choice) {
            case 1: doRegister(); return true;
            case 2: doLogin();    return true;
            case 3: return false;
            default: return true;
        }
    }

    /**
     * Main menu — shown after login. All the app features live here.
     */
    private boolean showMainMenu() {
        printHeader("Welcome, " + loggedInUser + "!");
        System.out.println("  1. Search Films");
        System.out.println("  2. Browse All Films");
        System.out.println("  3. Log a Film (rate & review)");
        System.out.println("  4. View My Diary");
        System.out.println("  5. View Another User's Diary");
        System.out.println("  6. Follow a User");
        System.out.println("  7. Unfollow a User");
        System.out.println("  8. View Activity Feed");
        System.out.println("  9. Logout");
        System.out.println("  0. Exit");
        printDivider();

        int choice = readInt("Choice: ", 0, 9);

        switch (choice) {
            case 1: doSearchFilms();    break;
            case 2: doBrowseFilms();    break;
            case 3: doLogFilm();        break;
            case 4: doViewMyDiary();    break;
            case 5: doViewUserDiary();  break;
            case 6: doFollow();         break;
            case 7: doUnfollow();       break;
            case 8: doViewFeed();       break;
            case 9: doLogout(); break;
            case 0: return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ACTION METHODS — each builds a JSON request, sends it, shows result
    // ═══════════════════════════════════════════════════════════════════

    private void doRegister() {
        System.out.print("  Username: ");
        String username = scanner.nextLine().trim();
        System.out.print("  Password: ");
        String password = scanner.nextLine().trim();

        // Build the JSON request
        JsonObject request = new JsonObject();
        request.addProperty("type", "REGISTER");
        request.addProperty("username", username);
        request.addProperty("password", password);

        // Send and receive
        JsonObject response = sendRequest(request);
        printResponse(response);
    }

    private void doLogin() {
        System.out.print("  Username: ");
        String username = scanner.nextLine().trim();
        System.out.print("  Password: ");
        String password = scanner.nextLine().trim();

        JsonObject request = new JsonObject();
        request.addProperty("type", "LOGIN");
        request.addProperty("username", username);
        request.addProperty("password", password);

        JsonObject response = sendRequest(request);
        printResponse(response);

        // If login succeeded, track BOTH the username and the server-issued token —
        // the token is what future requests actually prove identity with.
        if (response != null && "OK".equals(response.get("status").getAsString())) {
            loggedInUser = username;
            sessionToken = response.has("token") ? response.get("token").getAsString() : null;
        }
    }

    private void doLogout() {
        JsonObject request = new JsonObject();
        request.addProperty("type", "LOGOUT");
        request.addProperty("token", sessionToken);
        sendRequest(request);   // tell the server to invalidate the token server-side too
        loggedInUser = null;
        sessionToken = null;
        System.out.println("Logged out.\n");
    }

    private void doSearchFilms() {
        System.out.print("  Search query: ");
        String query = scanner.nextLine().trim();

        JsonObject request = new JsonObject();
        request.addProperty("type", "SEARCH_FILM");
        request.addProperty("query", query);

        JsonObject response = sendRequest(request);
        printResponse(response);
        printFilmsFromResponse(response);
    }

    private void doBrowseFilms() {
        JsonObject request = new JsonObject();
        request.addProperty("type", "LIST_FILMS");

        JsonObject response = sendRequest(request);
        printResponse(response);
        printFilmsFromResponse(response);
    }

    private void doLogFilm() {
        System.out.print("  Film ID (search first to find IDs): ");
        int filmId = readInt("", 1, 9999);

        System.out.print("  Rating (1.0 to 5.0): ");
        String ratingStr = scanner.nextLine().trim();
        double rating;
        try {
            rating = Double.parseDouble(ratingStr);
        } catch (NumberFormatException e) {
            System.out.println("  Invalid rating. Cancelled.\n");
            return;
        }

        System.out.print("  Review (optional, press Enter to skip): ");
        String review = scanner.nextLine().trim();

        // Use today's date
        String date = LocalDate.now().toString();

        JsonObject request = new JsonObject();
        request.addProperty("type", "LOG_FILM");
        request.addProperty("username", loggedInUser);
        request.addProperty("filmId", filmId);
        request.addProperty("rating", rating);
        request.addProperty("review", review);
        request.addProperty("date", date);
        request.addProperty("token", sessionToken);

        JsonObject response = sendRequest(request);
        printResponse(response);
    }

    private void doViewMyDiary() {
        JsonObject request = new JsonObject();
        request.addProperty("type", "GET_DIARY");
        request.addProperty("username", loggedInUser);
        request.addProperty("token", sessionToken);

        JsonObject response = sendRequest(request);
        printResponse(response);
        printDiaryFromResponse(response);
    }

    private void doViewUserDiary() {
        System.out.print("  Enter username to view: ");
        String targetUser = scanner.nextLine().trim();

        JsonObject request = new JsonObject();
        request.addProperty("type", "GET_USER_DIARY");
        request.addProperty("targetUser", targetUser);

        JsonObject response = sendRequest(request);
        printResponse(response);
        printDiaryFromResponse(response);
    }

    private void doFollow() {
        System.out.print("  Username to follow: ");
        String followee = scanner.nextLine().trim();

        JsonObject request = new JsonObject();
        request.addProperty("type", "FOLLOW");
        request.addProperty("follower", loggedInUser);
        request.addProperty("followee", followee);
        request.addProperty("token", sessionToken);

        JsonObject response = sendRequest(request);
        printResponse(response);
    }

    private void doUnfollow() {
        System.out.print("  Username to unfollow: ");
        String followee = scanner.nextLine().trim();

        JsonObject request = new JsonObject();
        request.addProperty("type", "UNFOLLOW");
        request.addProperty("follower", loggedInUser);
        request.addProperty("followee", followee);
        request.addProperty("token", sessionToken);

        JsonObject response = sendRequest(request);
        printResponse(response);
    }

    private void doViewFeed() {
        JsonObject request = new JsonObject();
        request.addProperty("type", "GET_FEED");
        request.addProperty("username", loggedInUser);
        request.addProperty("token", sessionToken);

        JsonObject response = sendRequest(request);
        printResponse(response);
        printDiaryFromResponse(response);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  NETWORK I/O — the actual socket communication
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send a JSON request to the server and read the response.
     *
     * This is the core CLIENT-SERVER COMMUNICATION:
     *   1. Serialize JsonObject → single-line JSON string
     *   2. Send it over the socket (println adds the newline delimiter)
     *   3. Read the response line (readLine blocks until newline arrives)
     *   4. Parse the response string back to JsonObject
     *
     * This is functionally identical to making an HTTP POST request
     * in a REST API — just at a lower protocol level (raw TCP).
     */
    private JsonObject sendRequest(JsonObject request) {
        try {
            // Serialize and send
            String jsonLine = request.toString();
            out.println(jsonLine);

            // Read the response (blocks until the server responds)
            String responseLine = in.readLine();

            if (responseLine == null) {
                System.err.println("  Server disconnected!");
                return null;
            }

            return JsonParser.parseString(responseLine).getAsJsonObject();

        } catch (IOException e) {
            System.err.println("  Communication error: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DISPLAY HELPERS — pretty-printing responses
    // ═══════════════════════════════════════════════════════════════════

    private void printResponse(JsonObject response) {
        if (response == null) {
            System.out.println("  [No response from server]\n");
            return;
        }

        String status = response.has("status") ? response.get("status").getAsString() : "?";
        String message = response.has("message") ? response.get("message").getAsString() : "";

        if ("OK".equals(status)) {
            System.out.println("  [OK] " + message);
        } else {
            System.out.println("  [ERROR] " + message);
        }
    }

    private void printFilmsFromResponse(JsonObject response) {
        if (response == null || !response.has("films")) return;

        JsonArray films = response.getAsJsonArray("films");
        if (films.size() == 0) {
            System.out.println("  No films found.\n");
            return;
        }

        System.out.println();
        System.out.println("  ┌────┬──────────────────────────────────────────┬──────┬─────────────────────────┬───────────┐");
        System.out.println("  │ ID │ Title                                    │ Year │ Director                │ Genre     │");
        System.out.println("  ├────┼──────────────────────────────────────────┼──────┼─────────────────────────┼───────────┤");

        for (int i = 0; i < films.size(); i++) {
            JsonObject film = films.get(i).getAsJsonObject();
            System.out.printf("  │ %-2d │ %-40s │ %4d │ %-23s │ %-9s │%n",
                film.get("id").getAsInt(),
                truncate(film.get("title").getAsString(), 40),
                film.get("year").getAsInt(),
                truncate(film.get("director").getAsString(), 23),
                truncate(film.get("genre").getAsString(), 9)
            );
        }
        System.out.println("  └────┴──────────────────────────────────────────┴──────┴─────────────────────────┴───────────┘");
        System.out.println();
    }

    private void printDiaryFromResponse(JsonObject response) {
        if (response == null) return;

        // Check both "diary" and "feed" keys
        JsonArray entries = null;
        if (response.has("diary")) entries = response.getAsJsonArray("diary");
        else if (response.has("feed")) entries = response.getAsJsonArray("feed");

        if (entries == null || entries.size() == 0) {
            System.out.println("  No entries to show.\n");
            return;
        }

        System.out.println();
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            String filmTitle = entry.get("filmTitle").getAsString();
            double rating = entry.get("rating").getAsDouble();
            String review = entry.has("review") && !entry.get("review").isJsonNull()
                          ? entry.get("review").getAsString() : "";
            String date = entry.get("date").getAsString();
            String username = entry.get("username").getAsString();

            String stars = ratingToStars(rating);

            System.out.printf("  %s  %s  %s (%.1f)%n", date, filmTitle, stars, rating);
            if (!review.isEmpty()) {
                System.out.println("         \"" + review + "\"");
            }
            System.out.println("         — " + username);
            if (i < entries.size() - 1) {
                System.out.println("  ─────────────────────────────────────────");
            }
        }
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════

    private int readInt(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String line = scanner.nextLine().trim();
            try {
                int val = Integer.parseInt(line);
                if (val >= min && val <= max) return val;
                System.out.println("  Please enter a number between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                System.out.println("  Please enter a valid number.");
            }
        }
    }

    private static String ratingToStars(double rating) {
        StringBuilder sb = new StringBuilder();
        int fullStars = (int) rating;
        boolean halfStar = (rating - fullStars) >= 0.5;
        for (int i = 0; i < fullStars; i++) sb.append("*");
        if (halfStar) sb.append(".5");
        return sb.toString() + "/" + "5";
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 2) + "..";
    }

    private void printHeader(String title) {
        System.out.println("  ╔══════════════════════════════════════════╗");
        System.out.printf("  ║  %-40s║%n", title);
        System.out.println("  ╠══════════════════════════════════════════╣");
    }

    private void printDivider() {
        System.out.println("  ╚══════════════════════════════════════════╝");
    }

    private void cleanup() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) { /* ignore */ }
        System.out.println("\nGoodbye!");
    }
}
