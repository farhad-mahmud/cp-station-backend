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
import Handlers.mock.AdminMockInterviewHandler;
import Handlers.mock.BookingsHandler;
import Handlers.mock.MentorPortalHandler;
import Handlers.mock.MentorsHandler;
import Handlers.mock.PaymentsHandler;
import auth.LoginHandler;
import auth.LogoutHandler;
import auth.MeHandler;
import auth.RegisterHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public class Server {

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(config.Env.get("PORT", "8082"));

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

        // ── Mock Interview ────────────────────────────────────────────────
        // Public catalogue: a logged out visitor can browse experts and only
        // meets the login gate at booking time.
        MentorsHandler mentorsHandler = new MentorsHandler();
        server.createContext("/mentors", new LoggingDecorator(mentorsHandler));
        server.createContext("/mentor-detail", new LoggingDecorator(mentorsHandler));
        server.createContext("/mentor-slots", new LoggingDecorator(mentorsHandler));
        server.createContext("/mentor-stacks", new LoggingDecorator(mentorsHandler));

        // Student booking lifecycle
        BookingsHandler bookingsHandler = new BookingsHandler();
        server.createContext("/bookings", new LoggingDecorator(new AuthDecorator(bookingsHandler)));
        server.createContext("/bookings/cancel", new LoggingDecorator(new AuthDecorator(bookingsHandler)));
        server.createContext("/my-bookings", new LoggingDecorator(new AuthDecorator(bookingsHandler)));
        server.createContext("/feedback", new LoggingDecorator(new AuthDecorator(bookingsHandler)));

        // Manual bKash payment submission
        PaymentsHandler paymentsHandler = new PaymentsHandler();
        server.createContext("/payments/instructions", new LoggingDecorator(new AuthDecorator(paymentsHandler)));
        server.createContext("/payments/submit", new LoggingDecorator(new AuthDecorator(paymentsHandler)));

        // Mentor workspace
        MentorPortalHandler mentorPortalHandler = new MentorPortalHandler();
        server.createContext("/mentor/me", new LoggingDecorator(new AuthDecorator(mentorPortalHandler)));
        server.createContext("/mentor/apply", new LoggingDecorator(new AuthDecorator(mentorPortalHandler)));
        server.createContext("/mentor/slots", new LoggingDecorator(new AuthDecorator(mentorPortalHandler)));
        server.createContext("/mentor/bookings", new LoggingDecorator(new AuthDecorator(mentorPortalHandler)));
        server.createContext("/mentor/complete", new LoggingDecorator(new AuthDecorator(mentorPortalHandler)));

        // Admin review: verifying a payment here is what confirms a booking
        AdminMockInterviewHandler adminMockHandler = new AdminMockInterviewHandler();
        server.createContext("/admin/mentors", new LoggingDecorator(new AuthDecorator(adminMockHandler)));
        server.createContext("/admin/bookings", new LoggingDecorator(new AuthDecorator(adminMockHandler)));
        server.createContext("/admin/payments", new LoggingDecorator(new AuthDecorator(adminMockHandler)));



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