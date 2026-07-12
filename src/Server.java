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
                  String error = "Internal server error";

            try {
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();
                } catch (Exception ignored) {}
            }   
        }
    }

    

}