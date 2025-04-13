package com.payaza.function;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.mongodb.client.*;
import com.mongodb.client.result.DeleteResult;
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
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(SystemStubsExtension.class)
@ExtendWith(MockitoExtension.class)
class ConnectionHandlerTest {
    private ConnectionHandler handler;

    @Mock
    Context context;

    @Mock
    LambdaLogger logger;


    @Mock
    MongoClient mockMongoClient;


    @Mock
    private FindIterable<Document> mockFindIterable;

    @Mock
    private FindIterable<Document> collectionmockFindIterable;


    @Mock
    MongoDatabase mockDatabase;

    @Mock
    MongoCollection<Document> usersCollection;

    @Mock
    MongoCollection<Document> musersCollection;
    @Mock
    private FindIterable<Document> mmockFindIterable;



    @Mock
    MongoCollection<Document> connectionsCollection;

    @Mock
    MongoCollection<Document> transientMessagesCollection;



    @SystemStub
    private EnvironmentVariables variables =
            new EnvironmentVariables("MONGODB_URI", "mongodb://localhost:27017")
                    .set("MONGODB_DATABASE", "testdb")
                    .set("AWS_REGION", "us-east-1")
                    .set("MESSAGE_TOPIC_ARN", "arn:aws:sns:us-east-1:123456789012:MyTopic");

    @BeforeEach
    public void setup() throws Exception{
        when(context.getLogger()).thenReturn(logger);

        doAnswer(call -> {
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
    void connect() throws Exception {

        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(connectionsCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(transientMessagesCollection);

            // Use reflection to set our mocks in the static fields
            setStaticField(ConnectionHandler.class, "mongoClient", mockMongoClient);
            setStaticField(ConnectionHandler.class, "database", mockDatabase);
            setStaticField(ConnectionHandler.class, "usersCollection", usersCollection);
            setStaticField(ConnectionHandler.class, "connectionsCollection", connectionsCollection);
            setStaticField(ConnectionHandler.class, "transientMessagesCollection", transientMessagesCollection);

            setStaticField(Middleware.class, "mongoClient", mockMongoClient);
            setStaticField(Middleware.class, "database", mockDatabase);
            setStaticField(Middleware.class, "usersCollection", usersCollection);
            setStaticField(Middleware.class, "connectionsCollection", connectionsCollection);

        } catch (Exception e) {
            e.printStackTrace();
        }


        APIGatewayV2WebSocketEvent event = new APIGatewayV2WebSocketEvent();
        APIGatewayV2WebSocketEvent. RequestContext requestContext = new APIGatewayV2WebSocketEvent.RequestContext();
        requestContext.setConnectionId("test-connection-id");
        requestContext.setRouteKey("$connect");
        event.setRequestContext(requestContext);
        event.setHeaders(Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer test-token",
                "User-Agent", "unknown",
                "X-Forwarded-For", ":::"
        ));
        event.setQueryStringParameters(Map.of(
                "user_id", "testuser"
        ));

        Document existingUser = new Document();
        ObjectId id = new ObjectId();
        existingUser.put("_id", id);
        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));
        when(usersCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        when(connectionsCollection.find(any(Bson.class))).thenReturn(collectionmockFindIterable);
        when(collectionmockFindIterable.first()).thenReturn(null);

        lenient().when(transientMessagesCollection.deleteOne(any(Document.class))).thenReturn(mock(DeleteResult.class));


        handler = new ConnectionHandler();

        lenient().doNothing().when(connectionsCollection).insertOne(any(Document.class));



        APIGatewayV2WebSocketResponse response = handler.handleRequest(event, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Connection processed successfully"));
        verify(connectionsCollection).insertMany(anyList());
    }

    @Test
    void connect_invalid_user() throws Exception {

        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(connectionsCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(transientMessagesCollection);

            // Use reflection to set our mocks in the static fields
            setStaticField(ConnectionHandler.class, "mongoClient", mockMongoClient);
            setStaticField(ConnectionHandler.class, "database", mockDatabase);
            setStaticField(ConnectionHandler.class, "usersCollection", usersCollection);
            setStaticField(ConnectionHandler.class, "connectionsCollection", connectionsCollection);
            setStaticField(ConnectionHandler.class, "transientMessagesCollection", transientMessagesCollection);

            setStaticField(Middleware.class, "mongoClient", mockMongoClient);
            setStaticField(Middleware.class, "database", mockDatabase);
            setStaticField(Middleware.class, "usersCollection", musersCollection);
            setStaticField(Middleware.class, "connectionsCollection", connectionsCollection);


        } catch (Exception e) {
            e.printStackTrace();
        }

        try (MockedStatic<SnsClient> snsClient = Mockito.mockStatic(SnsClient.class)) {
            snsClient.when(SnsClient::create).thenReturn(mock(SnsClient.class));
        }

        APIGatewayV2WebSocketEvent event = new APIGatewayV2WebSocketEvent();
        APIGatewayV2WebSocketEvent. RequestContext requestContext = new APIGatewayV2WebSocketEvent.RequestContext();
        requestContext.setConnectionId("test-connection-id");
        requestContext.setRouteKey("$connect");
        event.setRequestContext(requestContext);
        event.setHeaders(Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer test-token",
                "User-Agent", "unknown",
                "X-Forwarded-For", ":::"
        ));

        Document existingUser = new Document();
        ObjectId id = new ObjectId();
        existingUser.put("_id", id);

        event.setQueryStringParameters(Map.of(
                "user_id", id.toHexString()
        ));

        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));



        Document existingConnection = new Document();
        existingConnection.put("user_id", id);
        existingConnection.put("connection_id", "test-connection-id");


        when(musersCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        when(usersCollection.find(any(Bson.class))).thenReturn(mmockFindIterable);
        when(mmockFindIterable.first()).thenReturn(null);


        lenient().when(transientMessagesCollection.deleteOne(any(Document.class))).thenReturn(mock(DeleteResult.class));

        lenient().when(connectionsCollection.find(any(Bson.class))).thenReturn(collectionmockFindIterable);
        lenient().when(collectionmockFindIterable.first()).thenReturn(existingConnection);


        handler = new ConnectionHandler();

        lenient().doNothing().when(connectionsCollection).insertOne(any(Document.class));


        APIGatewayV2WebSocketResponse response = handler.handleRequest(event, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("User not found"));
    }


    @Test
    void disconnect(){
        APIGatewayV2WebSocketEvent event = new APIGatewayV2WebSocketEvent();
        APIGatewayV2WebSocketEvent. RequestContext requestContext = new APIGatewayV2WebSocketEvent.RequestContext();
        requestContext.setConnectionId("test-connection-id");
        requestContext.setRouteKey("$disconnect");
        event.setRequestContext(requestContext);
        event.setHeaders(Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer test-token",
                "User-Agent", "unknown",
                "X-Forwarded-For", ":::"
        ));

        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(connectionsCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(transientMessagesCollection);

            // Use reflection to set our mocks in the static fields
            setStaticField(ConnectionHandler.class, "mongoClient", mockMongoClient);
            setStaticField(ConnectionHandler.class, "database", mockDatabase);
            setStaticField(ConnectionHandler.class, "usersCollection", usersCollection);
            setStaticField(ConnectionHandler.class, "connectionsCollection", connectionsCollection);

            setStaticField(Middleware.class, "mongoClient", mockMongoClient);
            setStaticField(Middleware.class, "database", mockDatabase);
            setStaticField(Middleware.class, "usersCollection", usersCollection);
            setStaticField(Middleware.class, "connectionsCollection", connectionsCollection);


        } catch (Exception e) {
            e.printStackTrace();
        }

        Document existingUser = new Document();
        ObjectId id = new ObjectId();
        existingUser.put("_id", id);
        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));

        when(usersCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        lenient().when(transientMessagesCollection.deleteOne(any(Document.class))).thenReturn(mock(DeleteResult.class));

        Document existingConnection = new Document();
        existingConnection.put("user_id", id.toHexString());
        existingConnection.put("connection_id", "test-connection-id");


        lenient().when(connectionsCollection.find(any(Bson.class))).thenReturn(collectionmockFindIterable);
        lenient().when(collectionmockFindIterable.first()).thenReturn(existingConnection);


        handler = new ConnectionHandler();


        APIGatewayV2WebSocketResponse response = handler.handleRequest(event, context);


        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Connection processed successfully"));
        verify(connectionsCollection).deleteOne(any(Bson.class));

    }


}