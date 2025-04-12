package com.payaza.utils;

import com.amazonaws.lambda.thirdparty.com.google.gson.Gson;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.payaza.UnAuthorizedException;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;


public class Middleware {
    private static final Gson gson = new Gson();
    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;
    private static MongoCollection<Document> connectionsCollection;

    static {
        // Initialize MongoDB connection using environment variables
        String connectionString = System.getenv("MONGODB_CONNECTION_STRING");
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase(System.getenv("MONGODB_DATABASE"));
        usersCollection = database.getCollection("users");
        connectionsCollection = database.getCollection("connections");
    }


    public static void proxy(APIGatewayProxyRequestEvent request, String connectionId) {
        // Implement your authorization logic here
        // For example, check for a valid token in the headers
        header(request.getHeaders(), request, connectionId);
    }

    public static void socket(APIGatewayV2WebSocketEvent request, String connectionId) {
        // Implement your authorization logic here
        // For example, check for a valid token in the headers
         header(request.getHeaders(), request, connectionId);
    }

    private static <T> void header(Map<String, String> header, T request, String connectionId) {


        if (header == null || !header.containsKey("Authorization")) {
            throw new UnAuthorizedException("Authorization header required");
        }

        String token = header.get("Authorization");
        if (token == null || token.isEmpty()) {
            throw new UnAuthorizedException("Invalid token");
        }

        Document document = usersCollection.find(new Document("token", token)).first();

        if (document == null) {
            throw new UnAuthorizedException("Invalid token");
        }

        Document connection = connectionsCollection.find(new Document("connection_id", connectionId)).first();

        if (connection == null) {
            throw new UnAuthorizedException("Connection not found");
        }

        Map<String, String> userData = new HashMap<>();

        userData.put("user_id", document.getObjectId("_id").toString());
        userData.put("connection_id", connectionId);
        userData.put("email", document.getString("email"));
        userData.put("ttl", String.valueOf(document.getLong("ttl")));
        header.putAll(userData);

    }
}
