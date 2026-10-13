package model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * User — represents a registered user in our simplified Letterboxd.
 *
 * This prototype stores user records in its SQLite database. The exact
 * production storage used by Letterboxd is not established by the public
 * references included with this project.
 *
 *   Password hashing is done with SHA-256 here for simplicity.
 *   Real systems use bcrypt/scrypt/Argon2 (computationally expensive hashes)
 *   to resist brute-force attacks.
 */
public class User {

    private String username;
    private String passwordHash;

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    // --- Getters ---

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    // --- Static utility: hash a plaintext password ---

    /**
     * Hashes a password using SHA-256.
     * This converts "mypassword" → "89e01536ac207279..." (a fixed-length hex string).
     *
     * WHY HASH?
     *   This keeps the demo from storing the original password, but plain
     *   SHA-256 is too fast and is not salted. Production systems should use
     *   a password-specific algorithm such as Argon2id, scrypt, or bcrypt.
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(password.getBytes());

            // Convert byte array to hexadecimal string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java — this should never happen
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return "User{username='" + username + "'}";
    }
}
