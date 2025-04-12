package com.payaza.function;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.payaza.utils.APIResponse;
import com.payaza.utils.Collection;
import com.payaza.utils.Middleware;
import org.bson.Document;

public class HeartBeatHandler implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> usersCollection;


    static {
        String connectionString = System.getenv("MONGODB_URI");
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase(System.getenv("MONGODB_DATABASE"));
        usersCollection = database.getCollection(Collection.USERS_TABLE);
    }

    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        String connectionId = event.getRequestContext().getConnectionId();
        String body = event.getBody();

        try {
            // Process the heartbeat message here
            Middleware.socket(event, connectionId);

            Document heartbeat = new Document("$set", new Document("last_heartbeat", System.currentTimeMillis()));
            usersCollection.updateOne(new Document("user_id", connectionId), heartbeat);

            context.getLogger().log("Received heartbeat: " + body + " from connection: " + connectionId);
            return APIResponse.socketResponse(200, "Heartbeat received");
        } catch (Exception e) {
            context.getLogger().log("Error processing heartbeat: " + e.getMessage());
            return APIResponse.socketResponse(500, "Error processing heartbeat");
        }
    }
}
