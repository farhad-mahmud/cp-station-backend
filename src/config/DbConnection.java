package config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class DbConnection {
    private static final int MAX_POOL_SIZE = 10;
    private static final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>(MAX_POOL_SIZE);

    static {
        try {
            Class.forName("org.postgresql.Driver");
            // Pre-warm the pool with 3 connections to eliminate cold start overhead on boot
            for (int i = 0; i < 3; i++) {
                Connection c = createNewConnection();
                if (c != null) {
                    pool.offer(c);
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to pre-warm database connection pool: " + e.getMessage());
        }
    }

    private static Connection createNewConnection() throws Exception {
        String url = Env.get("DB_URL");
        String user = Env.get("DB_USER");
        String password = Env.get("DB_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException("Database configuration variables (DB_URL, DB_USER, DB_PASSWORD) are not configured in the environment or .env file.");
        }

        return DriverManager.getConnection(url, user, password);
    }

    public static Connection getConnection() throws Exception {
        Connection physicalConn = pool.poll();

        // If pool is empty or connection is dead, create a new one
        if (physicalConn == null || physicalConn.isClosed()) {
            physicalConn = createNewConnection();
        } else {
            // Verify connection is alive before handing it out
            try {
                if (!physicalConn.isValid(2)) { // 2 seconds timeout
                    try {
                        physicalConn.close();
                    } catch (Exception ignored) {}
                    physicalConn = createNewConnection();
                }
            } catch (Exception e) {
                physicalConn = createNewConnection();
            }
        }

        final Connection finalPhysical = physicalConn;

        // Return proxy connection that intercepts .close() to recycle it back to the pool
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            new InvocationHandler() {
                @Override
                public boolean equals(Object obj) {
                    return finalPhysical.equals(obj);
                }

                @Override
                public int hashCode() {
                    return finalPhysical.hashCode();
                }

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if (method.getName().equals("close")) {
                        // Return connection to pool if pool is not full
                        if (!finalPhysical.isClosed() && pool.size() < MAX_POOL_SIZE) {
                            if (pool.offer(finalPhysical)) {
                                return null; // Recycled successfully
                            }
                        }
                        // Otherwise close physical socket
                        finalPhysical.close();
                        return null;
                    }
                    // Delegate all other calls to physical connection
                    return method.invoke(finalPhysical, args);
                }
            }
        );
    }
}