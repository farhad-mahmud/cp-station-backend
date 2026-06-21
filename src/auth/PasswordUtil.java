package auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordUtil {

    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hashedBytes = digest.digest(password.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hashedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }

    public static boolean verify(String password, String salt, String expectedHash) {
        String actualHash = hash(password, salt);
        return actualHash.equals(expectedHash);
    }

    public static void main(String[] args) {
       String password = "testuser123";
    String storedSalt = "iaIxhMhiGDvBfLijza8UmQ==";
    String storedHash = "ouosBvxT1Xup0BttYkKEIo6Y6TKltk63xzGtV4ogo+s=";

    String computedHash = hash(password, storedSalt);

    System.out.println("Computed hash: " + computedHash);
    System.out.println("Stored hash:   " + storedHash);
    System.out.println("Match: " + computedHash.equals(storedHash));

    }
}