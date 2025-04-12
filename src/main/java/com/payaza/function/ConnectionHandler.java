package com.payaza.function;

import com.amazonaws.lambda.thirdparty.com.google.gson.Gson;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.payaza.domain.DMessage;
import com.payaza.utils.APIResponse;
import com.payaza.utils.Collection;
import com.payaza.utils.Middleware;
import org.bson.Document;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ConnectionHandler implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    private static final Gson gson = new Gson();
    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;
    private static MongoCollection<Document> connectionsCollection;
    private static MongoCollection<Document> transientMessagesCollection;
    private final SnsClient snsClient = SnsClient.create();
    private static final String MESSAGE_TOPIC_ARN = System.getenv("MESSAGE_TOPIC_ARN");

    static {
        String connectionString = System.getenv("MONGODB_URI");
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase(System.getenv("MONGODB_DATABASE"));
        usersCollection = database.getCollection(Collection.USERS_TABLE);
        connectionsCollection = database.getCollection(Collection.CONNECTION_TABLE);
        IndexOptions options = new IndexOptions().unique(true);
        connectionsCollection.createIndex(new Document("user_id", 1).append("connection_id", 1), options);
        transientMessagesCollection = database.getCollection(Collection.TRANSIENT_MESSAGES_TABLE);

    }


    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        String connectionId = event.getRequestContext().getConnectionId();
        String routeKey = event.getRequestContext().getRouteKey();

        try {
            Middleware.socket(event, connectionId);

            switch (routeKey) {
                case "$connect":
                    handleConnect(connectionId, event, context);
                    CompletableFuture.runAsync(() -> deliverTransientMessages(connectionId, event.getHeaders().get("username"), context));
                    break;
                case "$disconnect":
                    handleDisconnect(connectionId, context);
                    break;
                default:
                    context.getLogger().log("Unknown route: " + routeKey);
                    break;
            }

            return  APIResponse.socketResponse(200, "Connection processed successfully");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                context.getLogger().log("Invalid request: " + e.getMessage());
                return APIResponse.socketResponse(400, e.getMessage());
            }
            context.getLogger().log("Error handling connection: " + e.getMessage());
            return APIResponse.socketResponse(500, "Error processing connection");
        }
    }

    private void handleConnect(String connectionId, APIGatewayV2WebSocketEvent event, Context context) {
        Map<String, String> queryParams = event.getQueryStringParameters();
        String userId = queryParams.get("user_id");

        if (userId == null || userId.isEmpty()) {
            context.getLogger().log("User ID is missing in the query string");
            throw new IllegalArgumentException("User ID is required");
        }

        Document user = usersCollection.find(new Document("user_id", userId)).first();
        if (user == null) {
            context.getLogger().log("User not found: " + userId);
            throw new IllegalArgumentException("User not found");
        }

        Document existingConnection = connectionsCollection.find(new Document("user_id", userId).append("connection_id", connectionId)).first();
        if (existingConnection != null) {
            context.getLogger().log("User " + userId + " is already connected with connection ID: " + existingConnection.getString("connection_id"));
            throw new IllegalArgumentException("User is already connected");
        }

        List<Document> document = Arrays.asList(
                new Document("connection_id", connectionId),
                new Document("user_id", userId),
                new Document("connected_at", System.currentTimeMillis())
        );

        Document update = new Document("$set", new Document("connection_id", connectionId ));
        update.append("$set", new Document("connected_at", System.currentTimeMillis()));


        if (event.getHeaders() != null) {
            String userAgent = event.getHeaders().getOrDefault("User-Agent", "unknown");
            document.add(new Document("user_agent", userAgent));

            if (event.getHeaders().containsKey("X-Forwarded-For")) {
                update.append("$set", new Document("ip_address", event.getHeaders().get("X-Forwarded-For")));
                document.add(new Document("ip_address", event.getHeaders().get("X-Forwarded-For")));
                usersCollection.updateOne(new Document("user_id", userId), new Document("$set", new Document("ip_address", event.getHeaders().get("X-Forwarded-For"))),  new UpdateOptions().upsert(true));
            }
        }

        connectionsCollection.insertMany(document);

        context.getLogger().log("User " + userId + " connected with connection ID: " + connectionId);

    }


    private void handleDisconnect(String connectionId, Context context) {
        Document connection = connectionsCollection.find(new Document("connection_id", connectionId)).first();

        if (connection != null) {
            String userId = connection.getString("user_id");
//            usersCollection.updateOne(new Document("user_id", userId), new Document("$unset", new Document("connection_id", "")));
            connectionsCollection.deleteOne(new Document("connection_id", connectionId));
            context.getLogger().log("User " + userId + " disconnected with connection ID: " + connectionId);
        } else {
            context.getLogger().log("No user found with connection ID: " + connectionId);
        }
    }

    private void deliverTransientMessages(String connectionId, String username, Context context) {

        Document connection = connectionsCollection.find(new Document("connection_id", connectionId)).first();

        if (connection != null) {
            String userId = connection.getString("user_id");

            transientMessagesCollection
                .find(new Document("receiver_id", userId))
                .into(new ArrayList<>())
                .parallelStream()
                .forEach(message -> {
                    // Send the message to the WebSocket connection
                    context.getLogger().log("Delivering transient message to " + username + ": " + message.toJson());

                    message.append("connection_id", connectionId);

                    DMessage dm = gson.fromJson(message.toJson(), DMessage.class);

                    publishToSns(dm);
                });
        } else {
            context.getLogger().log("No user found with connection ID: " + connectionId);
        }
    }

    private void publishToSns(DMessage message) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        snsClient.publish(publishRequest -> publishRequest
                .topicArn(MESSAGE_TOPIC_ARN)
                //.messageAttributes(messageAttributes)
                .message(gson.toJson(message))
        );
    }
}
