import Handlers.GetCategoriesHandler;
import Handlers.GetTopicsByCategoryHandler;
import Handlers.GetTopicsHandler;
import auth.LoginHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress ;


public class Server {

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);


        // creating route /endpoint = similar to framework
        server.createContext("/", new RootHandler());

        // handle login 
        server.createContext("/login", new LoginHandler());
        // get resource by topics api .. in server . 

        server.createContext("/resources-by-topic", new GetResourcesByTopicsHandler());
        // get topics.. by api

        server.createContext("/topics" , new GetTopicsHandler());

        // post resources 
        server.createContext("/add-resource", new AddResourceHandler());

        // get categories..
        server.createContext("/categories", new GetCategoriesHandler());

        // get topics by categories 
        server.createContext("/topics-by-category", new GetTopicsByCategoryHandler());
        
        // get subtopics by topics..
        server.createContext("/subtopic-by-topic", new GetSubtopicsByTopics());
       
        //thread executor..
        server.setExecutor(null);
        server.start();

        System.out.println(" yo Server started on port 8080");
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