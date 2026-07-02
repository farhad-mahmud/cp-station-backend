package config ;

import java.sql.Connection;
import java.sql.DriverManager;

public class DbConnection {

    public static Connection getConnection() throws Exception {
        Class.forName("org.postgresql.Driver");
        
        String url = Env.get("DB_URL");
        String user = Env.get("DB_USER");
        String password = Env.get("DB_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException("Database configuration variables (DB_URL, DB_USER, DB_PASSWORD) are not configured in the environment or .env file.");
        }

        return DriverManager.getConnection(
                url,
                user,
                password
        );
    }
}