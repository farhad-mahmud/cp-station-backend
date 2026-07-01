package config ;

import java.sql.Connection;
import java.sql.DriverManager;

public class DbConnection {

    public static Connection getConnection() throws Exception {
        Class.forName("org.postgresql.Driver");
        
        String url = Env.get("DB_URL", "jdbc:postgresql://localhost:5432/postgres");
        String user = Env.get("DB_USER", "farhadmahmud");
        String password = Env.get("DB_PASSWORD", "1234");

        return DriverManager.getConnection(
                url,
                user,
                password
        );
    }
}