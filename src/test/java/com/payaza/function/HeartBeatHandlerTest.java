package com.payaza.function;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.mongodb.client.*;
import com.mongodb.client.model.UpdateOptions;
import com.payaza.utils.Middleware;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(SystemStubsExtension.class)
@ExtendWith(MockitoExtension.class)
class HeartBeatHandlerTest {

    private HeartBeatHandler handler;

    @Mock
    Context context;

    @Mock
    LambdaLogger logger;


    @Mock
    MongoClient mockMongoClient;

    @Mock
    MongoDatabase mockDatabase;

    @Mock
    private FindIterable<Document> mockFindIterable;

    @Mock
    private FindIterable<Document> collectionmockFindIterable;

    @Mock
    MongoCollection<Document> usersCollection;
    @Mock
    MongoCollection<Document> connectionsCollection;


    @SystemStub
    private EnvironmentVariables variables =
            new EnvironmentVariables("MONGODB_URI", "mongodb://localhost:27017")
                    .set("MONGODB_DATABASE", "testdb")
                    .set("AWS_REGION", "us-east-1")

                    .set("MESSAGE_TOPIC_ARN", "arn:aws:sns:us-east-1:123456789012:MyTopic");



    @BeforeEach
    public void setup() throws Exception{
        lenient().when(context.getLogger()).thenReturn(logger);

        lenient().doAnswer(call -> {
            String message = call.getArgument(0);
            System.out.println(message);
            return null;
        }).when(logger).log(anyString());

    }


    private static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }


    @Test
    void heartbeat() {
        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(connectionsCollection);

            setStaticField(HeartBeatHandler.class, "mongoClient", mockMongoClient);
            setStaticField(HeartBeatHandler.class, "database", mockDatabase);
            setStaticField(HeartBeatHandler.class, "usersCollection", usersCollection);

            setStaticField(Middleware.class, "mongoClient", mockMongoClient);
            setStaticField(Middleware.class, "database", mockDatabase);
            setStaticField(Middleware.class, "usersCollection", usersCollection);
            setStaticField(Middleware.class, "connectionsCollection", connectionsCollection);


        } catch (Exception e) {
            e.printStackTrace();
        }

        APIGatewayV2WebSocketEvent event = new APIGatewayV2WebSocketEvent();
        APIGatewayV2WebSocketEvent.RequestContext requestContext = new APIGatewayV2WebSocketEvent.RequestContext();
        requestContext.setConnectionId("test-connection-id");
        event.setRequestContext(requestContext);
        event.setHeaders(Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer test-token",
                "User-Agent", "unknown",
                "X-Forwarded-For", ":::"
        ));

        event.setBody("{ \"action\": \"heartbeat\" }");

        Document existingUser = new Document();
        ObjectId id = new ObjectId();
        existingUser.put("_id", id);
        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));

        when(usersCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        Document existingConnection = new Document();
        existingConnection.put("user_id", id);
        existingConnection.put("connection_id", "test-connection-id");


        when(connectionsCollection.find(any(Bson.class))).thenReturn(collectionmockFindIterable);
        when(collectionmockFindIterable.first()).thenReturn(existingConnection);


        handler = new HeartBeatHandler();

        APIGatewayV2WebSocketResponse response = handler.handleRequest(event, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Heartbeat received"));
        verify(usersCollection).updateOne(any(Document.class), any(Document.class), any(UpdateOptions.class));

    }
}