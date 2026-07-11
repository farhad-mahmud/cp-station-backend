package config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class Env {
    private static final Map<String, String> envMap = new HashMap<>();
    private static boolean loaded = false;
    private static long lastModified = 0;

    private static synchronized void load() {
        File envFile = new File(".env");
        if (envFile.exists()) {
            long modified = envFile.lastModified();
            if (loaded && modified <= lastModified) {
                return;
            }
            loaded = true;
            lastModified = modified;
            envMap.clear();
            
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int equalsIdx = line.indexOf('=');
                    if (equalsIdx > 0) {
                        String key = line.substring(0, equalsIdx).trim();
                        String value = line.substring(equalsIdx + 1).trim();
                        // Strip surrounding quotes if present
                        if (value.length() >= 2 && 
                            ((value.startsWith("\"") && value.endsWith("\"")) || 
                             (value.startsWith("'") && value.endsWith("'")))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        envMap.put(key, value);
                    }
                }
                System.out.println("Loaded/reloaded .env configuration (keys count: " + envMap.size() + ")");
            } catch (Exception e) {
                System.err.println("Warning: Failed to load .env file: " + e.getMessage());
            }
        } else {
            loaded = true;
        }
    }

    public static String get(String key) {
        return get(key, null);
    }

    public static String get(String key, String defaultValue) {
        load();
        // 1. Check system environment variable
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // 2. Check loaded .env variables
        value = envMap.get(key);
        if (value != null) {
            return value;
        }
        // 3. Fallback to default value
        return defaultValue;
    }
}
