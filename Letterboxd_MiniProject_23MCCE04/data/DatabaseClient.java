package data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.DiaryEntry;
import model.Film;
import protocol.Message;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseClient — lives inside an App Server (LetterboxdAppServer) and
 * stands in for a local "Database" object, except every method call now
 * goes out over a TCP socket to the DatabaseServer process instead of
 * touching JDBC directly.
 *
 * THIS IS THE CORE DISTRIBUTED-SYSTEMS LESSON OF THE WHOLE REDESIGN:
 *   In v1, `db.logFilm(...)` was an in-process method call — microseconds,
 *   can't partially fail, no network involved.
 *   In v2, `db.logFilm(...)` has the exact same Java signature/call-site,
 *   but is now secretly a NETWORK ROUND TRIP: it can be slow, it can time
 *   out, it can fail in ways a local call never can (DatabaseServer process
 *   is down, the socket resets mid-call, the response arrives corrupted).
 *   A method call that *looks* identical but now carries all of that risk
 *   is exactly the "distributed objects" pitfall real systems run into —
 *   RPC frameworks (gRPC, Java RMI) spend a lot of effort hiding this, but
 *   the risk is never actually gone, just hidden.
 *
 * CONNECTION STRATEGY — simplest correct option: open a brand-new socket
 * for every call, send one line, read one line, close. This avoids any
 * need to synchronize a shared connection across the many ClientHandler
 * threads that might call this concurrently (every call gets its own
 * socket, so there's no interleaving risk) — at the cost of a fresh TCP
 * handshake per call. A faster version would keep a pool of persistent
 * connections to DatabaseServer (exactly the same pooling idea as
 * ReplicaDatabase's connection pool) — left as a clearly-scoped
 * improvement; the comment block in DatabaseServer.java points to it.
 */
public class DatabaseClient {

    private final String host;
    private final int port;

    public DatabaseClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** The one network primitive every method below is built on: send one JSON line,
     *  block for the one JSON line response. */
    private JsonObject call(JsonObject request) {
        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            out.println(request.toString());
            String responseLine = in.readLine();
            if (responseLine == null) return Message.errorResponse("DatabaseServer closed connection");
            return Message.fromJson(responseLine);

        } catch (IOException e) {
            // This is the "partial failure" every distributed call has to handle —
            // a local method call could never throw "the other machine is unreachable."
            return Message.errorResponse("Cannot reach DatabaseServer at " + host + ":" + port + " — " + e.getMessage());
        }
    }

    private static boolean isOk(JsonObject response) {
        return response.has("status") && "OK".equals(response.get("status").getAsString());
    }

    public boolean registerUser(String username, String passwordHash) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "REGISTER");
        req.addProperty("username", username);
        req.addProperty("passwordHash", passwordHash);
        return isOk(call(req));
    }

    /** Returns the new session token on success, or null on bad credentials. */
    public String authenticate(String username, String passwordHash) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "AUTHENTICATE");
        req.addProperty("username", username);
        req.addProperty("passwordHash", passwordHash);
        JsonObject response = call(req);
        if (!isOk(response) || !response.has("token")) return null;
        return response.get("token").getAsString();
    }

    public void logout(String token) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "LOGOUT");
        req.addProperty("token", token);
        call(req);
    }

    public boolean userExists(String username) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "USER_EXISTS");
        req.addProperty("username", username);
        JsonObject response = call(req);
        return response.has("exists") && response.get("exists").getAsBoolean();
    }

    public List<Film> searchFilms(String query) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "SEARCH_FILM");
        req.addProperty("query", query);
        return filmsFrom(call(req));
    }

    public List<Film> getAllFilms() {
        JsonObject req = new JsonObject();
        req.addProperty("type", "LIST_FILMS");
        return filmsFrom(call(req));
    }

    /** Returns the server's message (e.g. the logged film's title wrapped in a sentence)
     *  on success, or null on failure — the router turns that into OK/ERROR for the end client.
     *  `token` must be the caller's own valid session token for `username`. */
    public String logFilm(String username, int filmId, double rating, String review, String date, String token) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "LOG_FILM");
        req.addProperty("username", username);
        req.addProperty("filmId", filmId);
        req.addProperty("rating", rating);
        req.addProperty("review", review);
        req.addProperty("date", date);
        req.addProperty("token", token);
        JsonObject response = call(req);
        if (!isOk(response)) return null;
        if (response.has("entry") && response.getAsJsonObject("entry").has("filmTitle")) {
            return response.getAsJsonObject("entry").get("filmTitle").getAsString();
        }
        return "";
    }

    /** Own diary — authenticated; `token` must belong to `username`.
     *  Returns null (NOT an empty list) if the session check failed — an empty list means
     *  "really has zero entries", null means "the call itself was rejected". Conflating the
     *  two was a real bug caught while testing this change: a rejected call used to come
     *  back from diaryFrom() as a silently-empty list, so a hijack attempt LOOKED like a
     *  successful read of an empty diary instead of the auth failure it actually was. */
    public List<DiaryEntry> getDiary(String username, String token) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "GET_DIARY");
        req.addProperty("username", username);
        req.addProperty("token", token);
        JsonObject response = call(req);
        if (!isOk(response)) return null;
        return diaryFrom(response, "diary");
    }

    /** Someone else's diary — public, no token needed. */
    public List<DiaryEntry> getPublicDiary(String targetUsername) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "GET_PUBLIC_DIARY");
        req.addProperty("username", targetUsername);
        JsonObject response = call(req);
        if (!isOk(response)) return null;
        return diaryFrom(response, "diary");
    }

    /** Same null-means-rejected contract as getDiary() above. */
    public List<DiaryEntry> getFeed(String username, String token) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "GET_FEED");
        req.addProperty("username", username);
        req.addProperty("token", token);
        JsonObject response = call(req);
        if (!isOk(response)) return null;
        return diaryFrom(response, "feed");
    }

    public boolean followUser(String follower, String followee, String token) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "FOLLOW");
        req.addProperty("follower", follower);
        req.addProperty("followee", followee);
        req.addProperty("token", token);
        return isOk(call(req));
    }

    public boolean unfollowUser(String follower, String followee, String token) {
        JsonObject req = new JsonObject();
        req.addProperty("type", "UNFOLLOW");
        req.addProperty("follower", follower);
        req.addProperty("followee", followee);
        req.addProperty("token", token);
        return isOk(call(req));
    }

    // ─── response -> domain object conversion ────────────────────────────

    private List<Film> filmsFrom(JsonObject response) {
        List<Film> films = new ArrayList<>();
        if (!response.has("films")) return films;
        JsonArray array = response.getAsJsonArray("films");
        for (int i = 0; i < array.size(); i++) {
            JsonObject f = array.get(i).getAsJsonObject();
            films.add(new Film(f.get("id").getAsInt(), f.get("title").getAsString(), f.get("year").getAsInt(),
                    f.get("director").getAsString(), f.get("genre").getAsString()));
        }
        return films;
    }

    private List<DiaryEntry> diaryFrom(JsonObject response, String key) {
        List<DiaryEntry> entries = new ArrayList<>();
        if (!response.has(key)) return entries;
        JsonArray array = response.getAsJsonArray(key);
        for (int i = 0; i < array.size(); i++) {
            JsonObject e = array.get(i).getAsJsonObject();
            entries.add(new DiaryEntry(e.get("id").getAsInt(), e.get("username").getAsString(),
                    e.get("filmId").getAsInt(), e.get("filmTitle").getAsString(), e.get("rating").getAsDouble(),
                    e.has("review") && !e.get("review").isJsonNull() ? e.get("review").getAsString() : "",
                    e.get("date").getAsString()));
        }
        return entries;
    }
}
