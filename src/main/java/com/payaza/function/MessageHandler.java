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
import com.payaza.exception.UnAuthorizedException;
import com.payaza.domain.DMessage;
import com.payaza.exception.ValidationException;
import com.payaza.utils.APIResponse;
import com.payaza.utils.Collection;
import com.payaza.utils.Middleware;
import org.bson.Document;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;

public class MessageHandler implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;
    private static MongoCollection<Document> friendsCollection;
    private static MongoCollection<Document> messagesCollection;
    private static SnsClient snsClient;
    private final Gson gson = new Gson();
    private static final String MESSAGE_TOPIC_ARN = System.getenv("MESSAGE_TOPIC_ARN");

    static {
        String connectionString = System.getenv("MONGODB_URI");
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase(System.getenv("MONGODB_DATABASE"));
        usersCollection = database.getCollection(Collection.USERS_TABLE);
        friendsCollection = database.getCollection(Collection.FRIENDS_TABLE);
        IndexOptions options = new IndexOptions().unique(true);
        friendsCollection.createIndex(new Document("user_id", 1).append("friend_id", 1), options);
        messagesCollection = database.getCollection(Collection.MESSAGES_TABLE);
        snsClient = SnsClient.create();
    }


    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        String connectionId = event.getRequestContext().getConnectionId();
        String body = event.getBody();

        try {
            Middleware.socket(event, connectionId);
            // Process the message here
            context.getLogger().log("Received message: " + body + " from connection: " + connectionId);


            String sender_id = event.getHeaders().get("user_id");

            DMessage message = gson.fromJson(body, DMessage.class);
            message = message.withSenderId(sender_id);
            if (message.contentType() == null) {
                message = message.withContentType("text");
            }
            if (message.receiverId() == null) {
                throw new ValidationException("Receiver ID is required");
            }
            if (message.content() == null) {
                throw new ValidationException("Message content is required");
            }

            // check if friendship exists, if not add it
            Document friend = friendsCollection.find(new Document("user_id", sender_id).append("friend_id", message.receiverId())).first();
            if (friend == null) {
                Document newFriend = new Document()
                        .append("user_id", sender_id)
                        .append("friend_id", message.receiverId())
                        .append("created_at", System.currentTimeMillis());
                friendsCollection.insertOne(newFriend);
            }

            // Store message if told to do so
            if (message.store()){
                storeMessage(message);
            }

            // Publish the message to SNS
            publishToSns(message);

            // Return a success response
            return APIResponse.socketResponse(200, "Message processed successfully");
        } catch (Exception e) {
            if (e instanceof UnAuthorizedException) {
                return APIResponse.socketResponse(401, e.getMessage());
            }
            if (e instanceof ValidationException) {
                return APIResponse.socketResponse(400, e.getMessage());
            }
            context.getLogger().log("Error processing message: " + e.getMessage());
            return APIResponse.socketResponse(500, "Error processing message");
        }
    }

    private void storeMessage(DMessage message) {
        Document messageDoc = new Document()
                .append("sender_id", message.senderId())
                .append("receiver_id", message.receiverId())
                .append("content", message.content())
                .append("timestamp", System.currentTimeMillis());

        messagesCollection.insertOne(messageDoc);
    }

    private void publishToSns(DMessage message) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        snsClient.publish(
            PublishRequest
                .builder()
                .topicArn(MESSAGE_TOPIC_ARN)
                .message(gson.toJson(message)).build()
        );
    }
}
