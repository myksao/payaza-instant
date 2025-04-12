package com.payaza.function;

import com.amazonaws.lambda.thirdparty.com.google.gson.Gson;
import com.amazonaws.lambda.thirdparty.com.google.gson.JsonObject;
import com.amazonaws.lambda.thirdparty.com.google.gson.JsonParser;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.payaza.utils.APIResponse;
import com.payaza.utils.Collection;
import org.bson.Document;
import org.mindrot.jbcrypt.BCrypt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public class AuthenticateHandler  implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Gson gson = new Gson();
    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;

    static {
        String connectionString = System.getenv("MONGODB_URI");
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase(System.getenv("MONGODB_DATABASE"));
        usersCollection = database.getCollection(Collection.USERS_TABLE);
        IndexOptions options = new IndexOptions().unique(true);
        usersCollection.createIndex(new Document("username", 1), options);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        context.getLogger().log("Received request: " + request.getPath());

        String path = request.getPath();
        String method = request.getHttpMethod();

        if ("/register".equals(path) && "POST".equals(method)) {
            return handleRegister(request);
        } else if ("/login".equals(path) && "POST".equals(method)) {
            return handleLogin(request);
        } else {
            return notFound(request);
        }
    }

    private APIGatewayProxyResponseEvent handleRegister(APIGatewayProxyRequestEvent request) {
        try {
            JsonObject requestBody = JsonParser.parseString(request.getBody()).getAsJsonObject();
            String username = requestBody.get("username").getAsString();
            String password = requestBody.get("password").getAsString();
            String email = requestBody.get("email").getAsString();

            Document existingUser = usersCollection.find(Filters.eq("username", username)).first();
            if (existingUser != null) {
                return APIResponse.proxyResponse(400, "Username already exists");
            }

            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

            Document newUser = new Document()
                    .append("username", username)
                    .append("password", hashedPassword)
                    .append("email", email)
                    .append("token", "")
                    .append("ttl", 0)
                    .append("last_heartbeat", System.currentTimeMillis())
                    .append("created_at", System.currentTimeMillis());


            usersCollection.insertOne(newUser);

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "User registered successfully");
            return APIResponse.proxyResponse(201, gson.toJson(responseBody));


        }catch (Exception e) {
            return APIResponse.proxyResponse(
                    500,
                    "Internal server error: " + e.getMessage()
            );
        }
    }

    private APIGatewayProxyResponseEvent handleLogin(APIGatewayProxyRequestEvent request) {
        try {

            JsonObject requestBody = JsonParser.parseString(request.getBody()).getAsJsonObject();
            String username = requestBody.get("username").getAsString();
            String password = requestBody.get("password").getAsString();

            Document user = usersCollection.find(Filters.eq("username", username)).first();

            if (user == null) {
                return APIResponse.proxyResponse(401, "Invalid username or password");
            }

            String hashedPassword = user.getString("password");
            if (!BCrypt.checkpw(password, hashedPassword)) {
                return APIResponse.proxyResponse(401, "Invalid username or password");
            }

            String token = UUID.randomUUID().toString();

            Document update = new Document("$set", new Document("token", token));

            usersCollection.updateOne(Filters.eq("username", username), update, new UpdateOptions().upsert(true));

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "Login successful");
            responseBody.put("token", token);
            responseBody.put("user_id", user.getObjectId("_id").toString());
            return APIResponse.proxyResponse(200, gson.toJson(responseBody));


        }catch (Exception e) {
            return APIResponse.proxyResponse(
                    500,
                    "Internal server error: " + e.getMessage()
            );
        }
    }

    private APIGatewayProxyResponseEvent notFound(APIGatewayProxyRequestEvent request) {

        return APIResponse.proxyResponse(
                404,
                "The requested resource was not found on the server."
        );
    }
}
