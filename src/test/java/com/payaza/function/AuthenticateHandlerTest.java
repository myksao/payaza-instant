package com.payaza.function;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import com.amazonaws.services.lambda.runtime.Context;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;



@ExtendWith(SystemStubsExtension.class)
@ExtendWith(MockitoExtension.class)
class AuthenticateHandlerTest {

    private AuthenticateHandler handler;

    @Mock
    Context context;

    @Mock
    LambdaLogger logger;


    @Mock
    MongoClient mockMongoClient;

    @Mock
    private FindIterable<Document> mockFindIterable;

    @Mock
    MongoDatabase mockDatabase;

    @Mock
    MongoCollection<Document> mockCollection;

    @SystemStub
    private EnvironmentVariables variables =
            new EnvironmentVariables("MONGODB_URI", "mongodb://localhost:27017").set("MONGODB_DATABASE", "testdb");

    @BeforeEach
    public void setup() throws Exception{
        when(context.getLogger()).thenReturn(logger);

        doAnswer(call -> {
            String message = call.getArgument(0);
            System.out.println(message);
            return null;
        }).when(logger).log(anyString());



        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(mockCollection);

            // Use reflection to set our mocks in the static fields
            setStaticField(AuthenticateHandler.class, "mongoClient", mockMongoClient);
            setStaticField(AuthenticateHandler.class, "database", mockDatabase);
            setStaticField(AuthenticateHandler.class, "usersCollection", mockCollection);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }


    @Test
    void signup() {

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPath("/register");
        event.setHttpMethod("POST");
        event.setBody("{ \"username\": \"testuser\", \"password\": \"password123\" }");
        event.setHeaders(Map.of("Content-Type", "application/json"));


        handler = new AuthenticateHandler();

        when(mockCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(null);

        doNothing().when(mockCollection).insertOne(any(Document.class));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertEquals(201, response.getStatusCode().intValue());
        assertTrue(response.getBody().contains("User registered successfully"));
        verify(mockCollection).insertOne(any(Document.class));
    }

    @Test
    public void existing_user() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPath("/register");
        event.setHttpMethod("POST");
        event.setBody("{\"username\":\"testuser\",\"password\":\"password123\" }");

        Document existingUser = new Document();
        existingUser.put("username", "testuser");

        handler = new AuthenticateHandler();

        when(mockCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);


        APIGatewayProxyResponseEvent response =  handler.handleRequest(event, context);


        assertEquals(400, response.getStatusCode().intValue());
        assertTrue(response.getBody().contains("Username already exists"));
        verify(mockCollection, never()).insertOne(any(Document.class));
    }


    @Test
    public void login() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPath("/login");
        event.setHttpMethod("POST");
        event.setBody("{\"username\":\"testuser\",\"password\":\"password123\" }");

        Document existingUser = new Document();
        ObjectId id = new ObjectId();
        existingUser.put("_id", id);
        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));

        handler = new AuthenticateHandler();

        when(mockCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertEquals(200, response.getStatusCode().intValue());
        assertTrue(response.getBody().contains("User logged in successfully"));
    }

    @Test
    public void login_invalid_credentials() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPath("/login");
        event.setHttpMethod("POST");
        event.setBody("{\"username\":\"testuser\",\"password\":\"wrongpassword\" }");

        Document existingUser = new Document();
        existingUser.put("username", "testuser");
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));

        handler = new AuthenticateHandler();

        when(mockCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertEquals(401, response.getStatusCode().intValue());
        assertTrue(response.getBody().contains("Invalid username or password"));
    }

    @Test
    public void login_user_not_found() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPath("/login");
        event.setHttpMethod("POST");
        event.setBody("{\"username\":\"nonexistentuser\",\"password\":\"password123\" }");

        handler = new AuthenticateHandler();

        when(mockCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(null);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertEquals(401, response.getStatusCode().intValue());
        assertTrue(response.getBody().contains("Invalid username or password"));
    }
}