package protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Message — shared helper for building and parsing JSON protocol messages.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHY A JSON PROTOCOL?
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * In a distributed system, the client and server are separate processes
 * (potentially on separate machines). They need an agreed-upon FORMAT to
 * communicate — this is the MESSAGE PROTOCOL.
 *
 * This project uses RAW TCP SOCKETS + JSON between Java services. The
 * browser-facing gateway uses local HTTP because browsers cannot open
 * arbitrary TCP sockets. This classroom protocol is independent of
 * Letterboxd's public API.
 *
 * PROTOCOL RULES:
 *   1. Every message is a SINGLE LINE of JSON text, terminated by '\n'
 *   2. Every CLIENT REQUEST has a "type" field (e.g., "LOGIN", "LOG_FILM")
 *   3. Every SERVER RESPONSE has a "status" field ("OK" or "ERROR")
 *   4. Additional data fields vary by message type
 *
 * This newline-delimited JSON approach is used by many real systems
 * (e.g., Docker Engine API, many logging pipelines).
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Message {

    // ─── Response builders ──────────────────────────────────────────────

    /** Build a success response: {"status":"OK", "message":"..."} */
    public static JsonObject okResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "OK");
        response.addProperty("message", message);
        return response;
    }

    /** Build an error response: {"status":"ERROR", "message":"..."} */
    public static JsonObject errorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ERROR");
        response.addProperty("message", message);
        return response;
    }

    /** Build a success response with a data payload (e.g., diary entries, search results) */
    public static JsonObject okResponseWithData(String message, String key, JsonArray data) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "OK");
        response.addProperty("message", message);
        response.add(key, data);
        return response;
    }

    // ─── JSON serialization ─────────────────────────────────────────────

    /** Convert a JsonObject to a single-line JSON string (for sending over socket) */
    public static String toJson(JsonObject obj) {
        return obj.toString();  // Gson's toString() produces compact single-line JSON
    }

    /** Parse a JSON string into a JsonObject (for receiving from socket) */
    public static JsonObject fromJson(String jsonString) {
        return JsonParser.parseString(jsonString).getAsJsonObject();
    }

    // ─── Field extraction helpers ───────────────────────────────────────

    /** Safely get a string field, or return defaultValue if missing */
    public static String getString(JsonObject obj, String field, String defaultValue) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return defaultValue;
    }

    /** Safely get a string field, or return "" if missing */
    public static String getString(JsonObject obj, String field) {
        return getString(obj, field, "");
    }

    /** Safely get an int field, or return defaultValue if missing */
    public static int getInt(JsonObject obj, String field, int defaultValue) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsInt();
        }
        return defaultValue;
    }

    /** Safely get a double field, or return defaultValue if missing */
    public static double getDouble(JsonObject obj, String field, double defaultValue) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsDouble();
        }
        return defaultValue;
    }
}
