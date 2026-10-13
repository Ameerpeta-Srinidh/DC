package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import data.DatabaseClient;
import model.DiaryEntry;
import model.Film;
import model.User;
import protocol.Message;

import java.util.List;

/**
 * RequestRouter — same client-facing protocol as v1 (REGISTER, LOGIN,
 * SEARCH_FILM, LOG_FILM, GET_FEED, ...), but notice what's DIFFERENT from
 * v1: `db` is now a DatabaseClient, not a Database. Every `db.xxx(...)`
 * call below reads exactly like a local method call — that's deliberate —
 * but is actually a full network round trip to a separate DatabaseServer
 * process. Validation logic (empty-field checks, rating range, self-follow
 * prevention) stays HERE, unchanged from v1, because it's about rejecting
 * garbage before it even leaves this process — no reason to pay for a
 * network round trip just to have the other side reject it.
 */
public class RequestRouter implements LineRouter {

    private final DatabaseClient db;

    public RequestRouter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String route(String jsonRequest) {
        try {
            JsonObject request = Message.fromJson(jsonRequest);
            String type = Message.getString(request, "type").toUpperCase();

            JsonObject response;
            switch (type) {
                case "REGISTER":       response = handleRegister(request);     break;
                case "LOGIN":          response = handleLogin(request);        break;
                case "LOGOUT":         response = handleLogout(request);       break;
                case "SEARCH_FILM":    response = handleSearchFilm(request);   break;
                case "LIST_FILMS":     response = handleListFilms(request);    break;
                case "LOG_FILM":       response = handleLogFilm(request);      break;
                case "GET_DIARY":      response = handleGetDiary(request);     break;
                case "GET_USER_DIARY": response = handleGetUserDiary(request); break;
                case "FOLLOW":         response = handleFollow(request);       break;
                case "UNFOLLOW":       response = handleUnfollow(request);     break;
                case "GET_FEED":       response = handleGetFeed(request);      break;
                default:
                    response = Message.errorResponse("Unknown request type: " + type);
            }
            return Message.toJson(response);

        } catch (Exception e) {
            return Message.toJson(Message.errorResponse("Server error: " + e.getMessage()));
        }
    }

    private JsonObject handleRegister(JsonObject request) {
        String username = Message.getString(request, "username");
        String password = Message.getString(request, "password");

        if (username.isEmpty() || password.isEmpty())
            return Message.errorResponse("Username and password are required.");
        if (username.length() < 3)
            return Message.errorResponse("Username must be at least 3 characters.");

        String hash = User.hashPassword(password);
        boolean success = db.registerUser(username, hash);
        return success
            ? Message.okResponse("Registered successfully! You can now log in.")
            : Message.errorResponse("Username '" + username + "' is already taken.");
    }

    private JsonObject handleLogin(JsonObject request) {
        String username = Message.getString(request, "username");
        String password = Message.getString(request, "password");
        String hash = User.hashPassword(password);
        String token = db.authenticate(username, hash);
        if (token == null) return Message.errorResponse("Invalid username or password.");

        // The client must hold onto this token and resend it on every request that
        // acts "as" this user (LOG_FILM, FOLLOW, UNFOLLOW, GET_DIARY, GET_FEED) —
        // see SessionManager's class comment for why this can't just be the username.
        JsonObject response = Message.okResponse("Welcome back, " + username + "!");
        response.addProperty("token", token);
        return response;
    }

    private JsonObject handleLogout(JsonObject request) {
        db.logout(Message.getString(request, "token"));
        return Message.okResponse("Logged out.");
    }

    private JsonObject handleSearchFilm(JsonObject request) {
        String query = Message.getString(request, "query");
        if (query.isEmpty()) return Message.errorResponse("Search query is required.");
        List<Film> results = db.searchFilms(query);
        return Message.okResponseWithData(results.size() + " film(s) found.", "films", filmsToJsonArray(results));
    }

    private JsonObject handleListFilms(JsonObject request) {
        List<Film> films = db.getAllFilms();
        return Message.okResponseWithData("Showing all " + films.size() + " films in catalog.", "films", filmsToJsonArray(films));
    }

    private JsonObject handleLogFilm(JsonObject request) {
        String username = Message.getString(request, "username");
        int filmId = Message.getInt(request, "filmId", -1);
        double rating = Message.getDouble(request, "rating", -1);
        String review = Message.getString(request, "review", "");
        String date = Message.getString(request, "date");
        String token = Message.getString(request, "token");

        if (username.isEmpty() || filmId < 0 || rating < 0 || date.isEmpty())
            return Message.errorResponse("Missing required fields: username, filmId, rating, date.");
        if (rating < 1.0 || rating > 5.0)
            return Message.errorResponse("Rating must be between 1.0 and 5.0.");

        String filmTitle = db.logFilm(username, filmId, rating, review, date, token);
        return (filmTitle != null)
            ? Message.okResponse("Logged '" + filmTitle + "' with " + rating + " stars!")
            : Message.errorResponse("Could not log film. Check that the film ID is valid.");
    }

    private JsonObject handleGetDiary(JsonObject request) {
        String username = Message.getString(request, "username");
        String token = Message.getString(request, "token");
        if (username.isEmpty()) return Message.errorResponse("Username is required.");
        List<DiaryEntry> entries = db.getDiary(username, token);
        if (entries == null) return Message.errorResponse("Invalid or expired session — please log in again.");
        return Message.okResponseWithData(username + "'s diary — " + entries.size() + " entry(ies).", "diary", diaryToJsonArray(entries));
    }

    private JsonObject handleGetUserDiary(JsonObject request) {
        String targetUser = Message.getString(request, "targetUser");
        if (targetUser.isEmpty()) return Message.errorResponse("Target username is required.");
        if (!db.userExists(targetUser)) return Message.errorResponse("User '" + targetUser + "' does not exist.");
        // Public profile view — no session required, same as the real app lets
        // anyone browse a public Letterboxd profile without being logged in as that user.
        List<DiaryEntry> entries = db.getPublicDiary(targetUser);
        if (entries == null) return Message.errorResponse("Could not load " + targetUser + "'s diary right now.");
        return Message.okResponseWithData(targetUser + "'s diary — " + entries.size() + " entry(ies).", "diary", diaryToJsonArray(entries));
    }

    private JsonObject handleFollow(JsonObject request) {
        String follower = Message.getString(request, "follower");
        String followee = Message.getString(request, "followee");
        String token = Message.getString(request, "token");
        if (follower.isEmpty() || followee.isEmpty()) return Message.errorResponse("Both follower and followee are required.");
        if (follower.equals(followee)) return Message.errorResponse("You cannot follow yourself.");
        boolean success = db.followUser(follower, followee, token);
        return success ? Message.okResponse("You are now following " + followee + "!")
                        : Message.errorResponse("Could not follow " + followee + ". User may not exist, session invalid, or you already follow them.");
    }

    private JsonObject handleUnfollow(JsonObject request) {
        String follower = Message.getString(request, "follower");
        String followee = Message.getString(request, "followee");
        String token = Message.getString(request, "token");
        if (follower.isEmpty() || followee.isEmpty()) return Message.errorResponse("Both follower and followee are required.");
        boolean success = db.unfollowUser(follower, followee, token);
        return success ? Message.okResponse("You have unfollowed " + followee + ".")
                        : Message.errorResponse("You are not following " + followee + " (or your session is invalid).");
    }

    private JsonObject handleGetFeed(JsonObject request) {
        String username = Message.getString(request, "username");
        String token = Message.getString(request, "token");
        if (username.isEmpty()) return Message.errorResponse("Username is required.");
        List<DiaryEntry> entries = db.getFeed(username, token);
        if (entries == null) return Message.errorResponse("Invalid or expired session — please log in again.");
        return Message.okResponseWithData("Activity feed — " + entries.size() + " recent entry(ies) from people you follow.", "feed", diaryToJsonArray(entries));
    }

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
