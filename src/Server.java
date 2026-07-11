import Handlers.GetCategoriesHandler;
import Handlers.GetTopicsByCategoryHandler;
import Handlers.ResourcesCRUDHandler;
import Handlers.SubtopicsCRUDHandler;
import Handlers.TopicsCRUDHandler;
import Handlers.VisitorStatsHandler;
import Handlers.UserProfileHandler;
import Handlers.UserSuggestionsHandler;
import Handlers.AiExplanationHandler;
import Handlers.AiFollowupHandler;
import Handlers.AdminAiSettingsHandler;
import auth.LoginHandler;
import auth.LogoutHandler;
import auth.MeHandler;
import auth.RegisterHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.Statement;


public class Server {

    public static void main(String[] args) throws Exception {



            // Auto-run schema migrations
        try (Connection conn = config.DbConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("ALTER TABLE topics ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 0");
            stmt.execute("ALTER TABLE subtopics ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 0");
            stmt.execute("ALTER TABLE resources ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 0");
            stmt.execute("ALTER TABLE topics ADD COLUMN IF NOT EXISTS is_interview BOOLEAN DEFAULT FALSE");
            stmt.execute("ALTER TABLE resources ADD COLUMN IF NOT EXISTS is_interview BOOLEAN DEFAULT FALSE");
            stmt.execute("ALTER TABLE resources ADD COLUMN IF NOT EXISTS solution_code TEXT DEFAULT ''");
            stmt.execute("ALTER TABLE resources ADD COLUMN IF NOT EXISTS solution_github_url VARCHAR(500) DEFAULT ''");
            stmt.execute("CREATE TABLE IF NOT EXISTS visitor_stats (id INT PRIMARY KEY, total_visits INT DEFAULT 0, unique_visits INT DEFAULT 0)");
            stmt.execute("INSERT INTO visitor_stats (id, total_visits, unique_visits) VALUES (1, 0, 0) ON CONFLICT DO NOTHING");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS name VARCHAR(255) DEFAULT 'Coder Name'");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS institute VARCHAR(255) DEFAULT 'Ex : BUBT'");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS solutions_contributed INT DEFAULT 0");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS completed_topics TEXT DEFAULT '[]'");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS completed_subtopics TEXT DEFAULT '[]'");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS solved_resources TEXT DEFAULT '[]'");
            stmt.execute("CREATE TABLE IF NOT EXISTS user_suggestions (" +
                         "id SERIAL PRIMARY KEY, " +
                         "user_id INT REFERENCES users(id) ON DELETE SET NULL, " +
                         "type VARCHAR(50) NOT NULL, " +
                         "title VARCHAR(255) NOT NULL, " +
                         "url TEXT NOT NULL, " +
                         "topic_id INT, " +
                         "status VARCHAR(50) DEFAULT 'pending', " +
                         "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                         ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS ai_explanations (" +
                         "id SERIAL PRIMARY KEY, " +
                         "resource_type VARCHAR(20) NOT NULL, " +
                         "resource_id INTEGER NOT NULL, " +
                         "content TEXT NOT NULL, " +
                         "created_at TIMESTAMP NOT NULL DEFAULT now(), " +
                         "updated_at TIMESTAMP NOT NULL DEFAULT now(), " +
                         "CONSTRAINT unique_resource_explanation UNIQUE (resource_type, resource_id)" +
                         ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS ai_followup_messages (" +
                         "id SERIAL PRIMARY KEY, " +
                         "user_id INTEGER REFERENCES users(id) ON DELETE CASCADE, " +
                         "resource_type VARCHAR(20) NOT NULL, " +
                         "resource_id INTEGER NOT NULL, " +
                         "role VARCHAR(20) NOT NULL, " +
                         "content TEXT NOT NULL, " +
                         "created_at TIMESTAMP NOT NULL DEFAULT now()" +
                         ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS ai_usage_log (" +
                         "id SERIAL PRIMARY KEY, " +
                         "user_id INTEGER REFERENCES users(id) ON DELETE SET NULL, " +
                         "resource_type VARCHAR(20), " +
                         "resource_id INTEGER, " +
                         "total_tokens INTEGER, " +
                         "thoughts_tokens INTEGER, " +
                         "created_at TIMESTAMP NOT NULL DEFAULT now()" +
                         ")");
            stmt.execute("CREATE TABLE IF NOT EXISTS ai_settings (" +
                         "key VARCHAR(50) PRIMARY KEY, " +
                         "value VARCHAR(255) NOT NULL" +
                         ")");
            stmt.execute("INSERT INTO ai_settings (key, value) VALUES ('daily_message_limit', '10') ON CONFLICT DO NOTHING");
            System.out.println("Database migrations applied successfully: sort_order, is_interview, visitor_stats, user_suggestions, users profiles, and AI tables verified.");
        } catch (Exception e) {
            System.err.println("Database migration failed: " + e.getMessage());
        }


        int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);  ;


        // creating route /endpoint = similar to framework
        server.createContext("/", new RootHandler());

        // handle login 
        server.createContext("/login", new LoginHandler());
        server.createContext("/register", new RegisterHandler());
        server.createContext("/logout", new LogoutHandler());
        
        // get resource by topics api .. in server . 
        server.createContext("/me", new MeHandler());

        server.createContext("/resources-by-topic", new GetResourcesByTopicsHandler());

        server.createContext("/topics" , new TopicsCRUDHandler());
        server.createContext("/subtopics", new SubtopicsCRUDHandler());
        server.createContext("/resources", new ResourcesCRUDHandler());
        server.createContext("/add-resource", new ResourcesCRUDHandler());

        // get categories..
        server.createContext("/categories", new GetCategoriesHandler());

        // get topics by categories 
        server.createContext("/topics-by-category", new GetTopicsByCategoryHandler());
        
        // get subtopics by topics..
        server.createContext("/subtopic-by-topic", new GetSubtopicsByTopics());
        
        // visitor tracking & insights stats
        server.createContext("/track-visit", new VisitorStatsHandler());
        server.createContext("/visitor-stats", new VisitorStatsHandler());

        // user profile persistence
        server.createContext("/user-profile", new UserProfileHandler());
        
        // user suggestions persistence
        server.createContext("/user-suggestions", new UserSuggestionsHandler());
        server.createContext("/my-suggestions", new UserSuggestionsHandler());

        // AI and Admin AI settings & tracking routes
        server.createContext("/ai/explanation", new AiExplanationHandler());
        server.createContext("/ai/followup", new AiFollowupHandler());
        AdminAiSettingsHandler adminAiHandler = new AdminAiSettingsHandler();
        server.createContext("/admin/ai/settings", adminAiHandler);
        server.createContext("/admin/ai/usage", adminAiHandler);
       

        //thread executor..
        server.setExecutor(null);
        server.start();

        System.out.println("yo Server started on port " + port);
    }

    // this class handles requests.. 
    static class RootHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {

                String response = "CP Backend is running";

                // sending http status..
                // status code 200 = ok,
                // 404 = not found..
                exchange.sendResponseHeaders(200, response.length());

                // gets pipe to browser ..
                OutputStream os = exchange.getResponseBody();
                //converts string to bytes..
                os.write(response.getBytes());
                // closing stream
                os.close();

            } catch (Exception e) {
                e.printStackTrace();
                  String error = "Server error: " + e.getMessage();

            try {
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();
                } catch (Exception ignored) {}
            }   
        }
    }

    

}