package config ;

import java.sql.Connection;
import java.sql.DriverManager;

public class DbConnection {

    private static final String URL =
            "jdbc:postgresql://localhost:5432/postgres";

    private static final String USER =
            "farhadmahmud";

    private static final String PASSWORD =
            "1234";

    public static Connection getConnection() throws Exception {

        return DriverManager.getConnection(
                URL,
                USER,
                PASSWORD
        );
    }
}