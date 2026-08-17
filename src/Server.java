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
import Handlers.decorators.AuthDecorator;
import Handlers.decorators.LoggingDecorator;
import auth.LoginHandler;
import auth.LogoutHandler;
import auth.MeHandler;
import auth.RegisterHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public class Server {

    public static void main(String[] args) throws Exception {

        int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // creating route /endpoint = similar to framework (wrapped with LoggingDecorator)
        server.createContext("/", new LoggingDecorator(new RootHandler()));

        // handle login 
        server.createContext("/login", new LoggingDecorator(new LoginHandler()));
        server.createContext("/register", new LoggingDecorator(new RegisterHandler()));
        server.createContext("/logout", new LoggingDecorator(new LogoutHandler()));
        
        // get resource by topics api .. in server . (wrapped with AuthDecorator & LoggingDecorator)
        server.createContext("/me", new LoggingDecorator(new AuthDecorator(new MeHandler())));

        server.createContext("/resources-by-topic", new LoggingDecorator(new GetResourcesByTopicsHandler()));

        server.createContext("/topics" , new LoggingDecorator(new TopicsCRUDHandler()));
        server.createContext("/subtopics", new LoggingDecorator(new SubtopicsCRUDHandler()));
        server.createContext("/resources", new LoggingDecorator(new ResourcesCRUDHandler()));
        server.createContext("/add-resource", new LoggingDecorator(new ResourcesCRUDHandler()));

        // get categories..
        server.createContext("/categories", new LoggingDecorator(new GetCategoriesHandler()));

        // get topics by categories 
        server.createContext("/topics-by-category", new LoggingDecorator(new GetTopicsByCategoryHandler()));
        
        // get subtopics by topics..
        server.createContext("/subtopic-by-topic", new LoggingDecorator(new GetSubtopicsByTopics()));
        
        // visitor tracking & insights stats
        server.createContext("/track-visit", new LoggingDecorator(new VisitorStatsHandler()));
        server.createContext("/visitor-stats", new LoggingDecorator(new VisitorStatsHandler()));

        // user profile persistence (wrapped with AuthDecorator & LoggingDecorator)
        server.createContext("/user-profile", new LoggingDecorator(new AuthDecorator(new UserProfileHandler())));
        
        // user suggestions persistence
        server.createContext("/user-suggestions", new LoggingDecorator(new UserSuggestionsHandler()));
        server.createContext("/my-suggestions", new LoggingDecorator(new UserSuggestionsHandler()));

        // AI and Admin AI settings & tracking routes
        server.createContext("/ai/explanation", new LoggingDecorator(new AiExplanationHandler()));
        server.createContext("/ai/followup", new LoggingDecorator(new AiFollowupHandler()));
        AdminAiSettingsHandler adminAiHandler = new AdminAiSettingsHandler();
        server.createContext("/admin/ai/settings", new LoggingDecorator(new AuthDecorator(adminAiHandler)));
        server.createContext("/admin/ai/usage", new LoggingDecorator(new AuthDecorator(adminAiHandler)));

       

        //thread executor..
        server.setExecutor(null);
        server.start();

        System.out.println("yo Server started on port " + port);
    }

    // this class handles requests using Template Method Pattern
    static class RootHandler extends Handlers.AbstractHttpHandler {
        @Override
        protected void processRequest(HttpExchange exchange) throws Exception {
            String response = "CP Backend is running";
            sendJSON(exchange, 200, response);
        }
    }


    

}