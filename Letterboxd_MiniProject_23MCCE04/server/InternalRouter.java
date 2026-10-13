package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import data.PrimaryDatabase;
import data.ReplicaDatabase;
import data.ReplicationManager;
import data.SessionManager;
import model.DiaryEntry;
import model.Film;
import model.User;
import protocol.Message;

import java.util.List;

/**
 * InternalRouter — the "backend-for-backend" protocol router, living inside
 * DatabaseServer. It plays the exact same architectural role RequestRouter
 * played in v1 — parse a JSON line, dispatch on "type", return a JSON line
 * — except its CLIENTS are App Server processes (via DatabaseClient), not
 * end users. This is intentional symmetry: the same request/response
 * pattern, reused one layer down the stack, is exactly how real multi-tier
 * distributed systems are built (app tier -> data tier, each tier talking
 * the "same shape" of protocol to the one below it).
 *
 * WRITE PATH: PrimaryDatabase does the write, then — ONLY if it succeeded —
 * this router tells ReplicationManager to mirror the effect onto the
 * replica asynchronously. The client gets its response as soon as the
 * PRIMARY write is durable; it does NOT wait for replication. That's a
 * deliberate choice (favors availability/latency over read-your-writes
 * consistency) — see the project write-up for the trade-off discussion.
 *
 * READ PATH: goes straight to ReplicaDatabase, never touches the primary.
 */
public class InternalRouter implements LineRouter {

    private final PrimaryDatabase primary;
    private final ReplicaDatabase replica;
    private final ReplicationManager replication;
    private final SessionManager sessions;

    public InternalRouter(PrimaryDatabase primary, ReplicaDatabase replica,
                           ReplicationManager replication, SessionManager sessions) {
        this.primary = primary;
        this.replica = replica;
        this.replication = replication;
        this.sessions = sessions;
    }

    @Override
    public String route(String jsonRequest) {
        try {
            JsonObject request = Message.fromJson(jsonRequest);
            String type = Message.getString(request, "type").toUpperCase();

            JsonObject response;
            switch (type) {
                case "REGISTER":      response = handleRegister(request);     break;
                case "AUTHENTICATE":  response = handleAuthenticate(request); break;
                case "LOGOUT":        response = handleLogout(request);       break;
                case "USER_EXISTS":   response = handleUserExists(request);   break;
                case "SEARCH_FILM":   response = handleSearchFilm(request);   break;
                case "LIST_FILMS":    response = handleListFilms(request);    break;
                case "LOG_FILM":      response = handleLogFilm(request);      break;
                case "GET_DIARY":     response = handleGetDiary(request);     break;
                case "GET_PUBLIC_DIARY": response = handleGetPublicDiary(request); break;
                case "FOLLOW":        response = handleFollow(request);       break;
                case "UNFOLLOW":      response = handleUnfollow(request);     break;
                case "GET_FEED":      response = handleGetFeed(request);      break;
                default:
                    response = Message.errorResponse("Unknown internal request type: " + type);
            }
            return Message.toJson(response);

        } catch (Exception e) {
            return Message.toJson(Message.errorResponse("DatabaseServer error: " + e.getMessage()));
        }
    }

    // ─── Writes (primary, then async-enqueue replication) ───────────────

    private JsonObject handleRegister(JsonObject req) {
        String username = Message.getString(req, "username");
        String passwordHash = Message.getString(req, "passwordHash");
        boolean ok = primary.registerUser(username, passwordHash);
        if (ok) {
            replication.replicateUserRegistered(username, passwordHash);
            return Message.okResponse("registered");
        }
        return Message.errorResponse("username taken");
    }

    private JsonObject handleAuthenticate(JsonObject req) {
        String username = Message.getString(req, "username");
        String passwordHash = Message.getString(req, "passwordHash");
        User user = primary.authenticateUser(username, passwordHash);
        if (user == null) return Message.errorResponse("invalid credentials");

        // Mint a fresh opaque token for this login — this is the ONLY place a
        // token is ever created, and it only happens after the password hash
        // has actually matched what's in PrimaryDatabase.
        String token = sessions.createSession(username);
        JsonObject response = Message.okResponse("ok");
        response.addProperty("token", token);
        return response;
    }

    private JsonObject handleLogout(JsonObject req) {
        sessions.invalidate(Message.getString(req, "token"));
        return Message.okResponse("logged out");
    }

    /** The trust-boundary check every "act as this user" operation below runs first:
     *  does the caller's token actually belong to the username it claims to be acting as?
     *  Returns an error response to short-circuit with, or null if the session checks out. */
    private JsonObject requireSession(JsonObject req, String claimedUsernameField) {
        String token = Message.getString(req, "token");
        String claimedUsername = Message.getString(req, claimedUsernameField);
        if (!sessions.isValidFor(token, claimedUsername)) {
            return Message.errorResponse("Invalid or expired session — please log in again.");
        }
        return null;
    }

    private JsonObject handleLogFilm(JsonObject req) {
        JsonObject authError = requireSession(req, "username");
        if (authError != null) return authError;

        String username = Message.getString(req, "username");
        int filmId = Message.getInt(req, "filmId", -1);
        double rating = Message.getDouble(req, "rating", -1);
        String review = Message.getString(req, "review", "");
        String date = Message.getString(req, "date");

        PrimaryDatabase.LogFilmResult result = primary.logFilm(username, filmId, rating, review, date);
        if (result == null) return Message.errorResponse("film not found or insert failed");

        replication.replicateFilmLogged(result);

        JsonObject data = new JsonObject();
        data.addProperty("filmTitle", result.entry.getFilmTitle());
        JsonObject response = Message.okResponse("logged");
        response.add("entry", data);
        return response;
    }

    private JsonObject handleFollow(JsonObject req) {
        JsonObject authError = requireSession(req, "follower");
        if (authError != null) return authError;

        String follower = Message.getString(req, "follower");
        String followee = Message.getString(req, "followee");
        PrimaryDatabase.FollowResult result = primary.followUser(follower, followee);
        if (!result.success) return Message.errorResponse("could not follow");
        replication.replicateFollow(follower, followee, result.backfilledEntryIds);
        return Message.okResponse("followed");
    }

    private JsonObject handleUnfollow(JsonObject req) {
        JsonObject authError = requireSession(req, "follower");
        if (authError != null) return authError;

        String follower = Message.getString(req, "follower");
        String followee = Message.getString(req, "followee");
        // (unfollowUser's boolean return still comes straight from PrimaryDatabase here;
        //  only the replica side needs the extra mirroring call.)
        boolean ok = primaryUnfollow(follower, followee);
        if (ok) replication.replicateUnfollow(follower, followee);
        return ok ? Message.okResponse("unfollowed") : Message.errorResponse("not following");
    }

    private boolean primaryUnfollow(String follower, String followee) {
        return primary.unfollowUser(follower, followee);
    }

    // ─── Reads (replica only) ─────────────────────────────────────────────

    private JsonObject handleUserExists(JsonObject req) {
        boolean exists = replica.userExists(Message.getString(req, "username"));
        JsonObject response = Message.okResponse(exists ? "exists" : "missing");
        response.addProperty("exists", exists);
        return response;
    }

    private JsonObject handleSearchFilm(JsonObject req) {
        List<Film> results = replica.searchFilms(Message.getString(req, "query"));
        return Message.okResponseWithData(results.size() + " found", "films", filmsToJsonArray(results));
    }

    private JsonObject handleListFilms(JsonObject req) {
        List<Film> films = replica.getAllFilms();
        return Message.okResponseWithData("all films", "films", filmsToJsonArray(films));
    }

    /** "My diary" — the caller must hold a valid session FOR that exact username. */
    private JsonObject handleGetDiary(JsonObject req) {
        JsonObject authError = requireSession(req, "username");
        if (authError != null) return authError;

        List<DiaryEntry> entries = replica.getDiary(Message.getString(req, "username"));
        return Message.okResponseWithData(entries.size() + " entries", "diary", diaryToJsonArray(entries));
    }

    /** Someone else's diary — deliberately PUBLIC, same as browsing anyone's public
     *  Letterboxd profile; no session required. Kept as a separate internal type
     *  from handleGetDiary precisely so "my diary" can be authenticated without
     *  also locking up everyone else's public profile view. */
    private JsonObject handleGetPublicDiary(JsonObject req) {
        String targetUser = Message.getString(req, "username");
        List<DiaryEntry> entries = replica.getDiary(targetUser);
        return Message.okResponseWithData(entries.size() + " entries", "diary", diaryToJsonArray(entries));
    }

    private JsonObject handleGetFeed(JsonObject req) {
        JsonObject authError = requireSession(req, "username");
        if (authError != null) return authError;

        List<DiaryEntry> entries = replica.getFeed(Message.getString(req, "username"));
        return Message.okResponseWithData(entries.size() + " entries", "feed", diaryToJsonArray(entries));
    }

    // ─── JSON conversion helpers (same shape as v1 RequestRouter) ────────

    private JsonArray filmsToJsonArray(List<Film> films) {
        JsonArray array = new JsonArray();
        for (Film f : films) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", f.getId());
            obj.addProperty("title", f.getTitle());
            obj.addProperty("year", f.getYear());
            obj.addProperty("director", f.getDirector());
            obj.addProperty("genre", f.getGenre());
            array.add(obj);
        }
        return array;
    }

    private JsonArray diaryToJsonArray(List<DiaryEntry> entries) {
        JsonArray array = new JsonArray();
        for (DiaryEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", e.getId());
            obj.addProperty("username", e.getUsername());
            obj.addProperty("filmId", e.getFilmId());
            obj.addProperty("filmTitle", e.getFilmTitle());
            obj.addProperty("rating", e.getRating());
            obj.addProperty("review", e.getReview());
            obj.addProperty("date", e.getDate());
            array.add(obj);
        }
        return array;
    }
}
