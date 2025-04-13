package com.payaza.utils;

import com.amazonaws.lambda.thirdparty.com.google.gson.Gson;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.payaza.exception.UnAuthorizedException;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


public class Middleware {
    private static final Gson gson = new Gson();
    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;
    private static MongoCollection<Document> connectionsCollection;

    static {
        // Initialize MongoDB connection using environment variables
        String connectionString = System.getenv("MONGODB_URI");
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

        Document document = usersCollection.find(new Document("token", token.split("Bearer")[1])).first();

        if (document == null) {
            throw new UnAuthorizedException("Invalid token");
        }

        if (!Objects.equals(connectionId, "$connect")) {
            Document connection = connectionsCollection.find(new Document("connection_id", connectionId)).first();

            if (connection == null) {
                throw new UnAuthorizedException("Connection not found");
            }
        }


        Map<String, String> info = new HashMap<>(header);
        info.put("user_id", document.getObjectId("_id").toString());
        info.put("connection_id", connectionId);
        info.put("ttl", String.valueOf(document.getLong("ttl")));

       if (request instanceof  APIGatewayProxyRequestEvent) {
            ((APIGatewayProxyRequestEvent) request).setHeaders(info);
        } else if (request instanceof APIGatewayV2WebSocketEvent) {
            ((APIGatewayV2WebSocketEvent) request).setHeaders(info);
        }

    }
}
