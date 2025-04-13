package com.payaza.function;

import com.amazonaws.lambda.thirdparty.com.google.gson.Gson;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import com.payaza.domain.DMessage;
import com.payaza.utils.Collection;
import org.bson.Document;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;

import java.net.URI;

public class SNSEventProcessor implements RequestHandler<SNSEvent, Void> {

    private final Gson gson = new Gson();
    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;
    private static MongoCollection<Document> connectionsCollection;
    private static MongoCollection<Document> transientMessagesCollection;
    private static final String API_ENDPOINT = System.getenv("API_ENDPOINT");

    static {
        String connectionString = System.getenv("MONGODB_URI");
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase(System.getenv("MONGODB_DATABASE"));
        usersCollection = database.getCollection(Collection.USERS_TABLE);
        connectionsCollection = database.getCollection(Collection.CONNECTION_TABLE);
        transientMessagesCollection = database.getCollection(Collection.TRANSIENT_MESSAGES_TABLE);
    }


    @Override
    public Void handleRequest(SNSEvent event, Context context) {
        ApiGatewayManagementApiClient apiClient = ApiGatewayManagementApiClient.builder()
                .endpointOverride(URI.create(API_ENDPOINT))
                .build();

        event.getRecords().parallelStream().forEach( record -> {
            SNSEvent.SNS sns = record.getSNS();

            context.getLogger().log("SNS Message: " + sns.getMessage());
            context.getLogger().log("SNS Subject: " + sns.getSubject());

            try {
                DMessage message = gson.fromJson(sns.getMessage(), DMessage.class);

                // Find the connection ID for the user, but check if user is online i.e if user current last heartbeat is less than 5 seconds
                Document connection = connectionsCollection.find(new Document("user_id", message.receiverId())).first();

                if (connection != null) {
                    String connectionId = connection.getString("connection_id");
                    context.getLogger().log("Sending message to connection: " + connectionId);

                    Document user = usersCollection.find(new Document("user_id", message.receiverId())).first();

                    // check if user is online
                    if (user != null && user.getLong("last_heartbeat") != null) {
                        long lastHeartbeat = user.getLong("last_heartbeat");
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastHeartbeat > 5000) {
                            context.getLogger().log("User is offline: " + message.receiverId());
                            transientMessagesCollection.insertOne(new Document("receiver_id", message.receiverId())
                                    .append("connection_id", connectionId)
                                    .append("sender_id", message.senderId())
                                    .append("content", gson.toJson(message))
                                    .append("ttl", user.get("ttl") == null ? 0 : user.getLong("ttl"))
                                    .append("timestamp", System.currentTimeMillis()));
                            context.getLogger().log("Message stored in transient messages collection");
                            return;
                        }
                    }

                    String payload = gson.toJson(message);

                    // Send the message to the WebSocket connection
                    apiClient.postToConnection(r -> r
                            .connectionId(connectionId)
                            .data(SdkBytes.fromUtf8String(payload)));
                } else {
                    Document receiver = usersCollection.find(new Document("user_id", message.receiverId())).first();
                    context.getLogger().log("User is not connected: " + message.senderId());
                    transientMessagesCollection.insertOne(new Document("receiver_id", message.receiverId())
                            .append("sender_id", message.senderId())
                            .append("message", gson.toJson(message))
                            .append("ttl", receiver.get("ttl") == null ? 0 : receiver.getLong("ttl"))
                            .append("timestamp", System.currentTimeMillis()));
                    context.getLogger().log("Message stored in transient messages collection");
                }

            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
            }
        } );
        return null;
    }
}
