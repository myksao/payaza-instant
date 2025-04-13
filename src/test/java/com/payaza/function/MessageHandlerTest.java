package com.payaza.function;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.mongodb.client.*;
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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
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
class MessageHandlerTest {
    private MessageHandler handler;


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
    private FindIterable<Document> friendsCollectionFindIterable;

    @Mock
    MongoCollection<Document> usersCollection;
    @Mock
    MongoCollection<Document> friendsCollection;

    @Mock
    MongoCollection<Document> messagesCollection;
    @Mock
    MongoCollection<Document> connectionsCollection;

    @Mock
    SnsClient snsClient;

    @SystemStub
    private EnvironmentVariables variables =
            new EnvironmentVariables("MONGODB_URI", "mongodb://localhost:27017")
                    .set("MONGODB_DATABASE", "testdb")
                    .set("AWS_REGION", "us-east-1")

                    .set("AWS_ACCESS_KEY_ID", "fakeAccessKey")
                    .set("AWS_SECRET_ACCESS_KEY", "fakeSecretKey")
                    .set("AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/fakeRole")
                    .set("AWS_WEB_IDENTITY_TOKEN_FILE", "fakeTokenFile")


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

    private static void injectField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Test
    public void process_message_with_a_new_friend(){

        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(friendsCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(messagesCollection);

            injectField(MessageHandler.class, "mongoClient", mockMongoClient);
            injectField(MessageHandler.class, "database", mockDatabase);
            injectField(MessageHandler.class, "usersCollection", usersCollection);
            injectField(MessageHandler.class, "friendsCollection", friendsCollection);
            injectField(MessageHandler.class, "messagesCollection", messagesCollection);
            injectField(MessageHandler.class, "snsClient", snsClient);

            injectField(Middleware.class, "mongoClient", mockMongoClient);
            injectField(Middleware.class, "database", mockDatabase);
            injectField(Middleware.class, "usersCollection", usersCollection);
            injectField(Middleware.class, "connectionsCollection", connectionsCollection);


        } catch (Exception e) {
            e.printStackTrace();
        }

        try (MockedStatic<SnsClient> snsClient = Mockito.mockStatic(SnsClient.class)) {
            snsClient.when(SnsClient::create).thenReturn(mock(SnsClient.class));
        }

        lenient().when(snsClient.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().build());


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

        event.setBody("{ \"receiver_id\": \"1234567890\", \"content\": \"Hello, friend!\", \"content_type\": \"text\", \"store\": true }");

        Document existingUser = new Document();
        ObjectId id = new ObjectId();
        existingUser.put("_id", id);
        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));

        when(usersCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        when(friendsCollection.find(any(Bson.class))).thenReturn(friendsCollectionFindIterable);
        when(friendsCollectionFindIterable.first()).thenReturn(null);

        lenient().doNothing().when(friendsCollection).insertOne(any(Document.class));
        lenient().doNothing().when(messagesCollection).insertOne(any(Document.class));

        Document existingConnection = new Document();
        existingConnection.put("user_id", id);
        existingConnection.put("connection_id", "test-connection-id");


        when(connectionsCollection.find(any(Bson.class))).thenReturn(collectionmockFindIterable);
        when(collectionmockFindIterable.first()).thenReturn(existingConnection);

        handler = new MessageHandler();

        APIGatewayV2WebSocketResponse response = handler.handleRequest(event, context);


        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Message processed successfully"));
        verify(friendsCollection).insertOne(any(Document.class));
        verify(messagesCollection).insertOne(any(Document.class));
        verify(snsClient).publish(any(PublishRequest.class));



    }

}

