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
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(SystemStubsExtension.class)
@ExtendWith(MockitoExtension.class)
class MessageRouterTest {
    private MessageRouter router;

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

    @Mock
    LambdaClient lambdaClient;


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

    private static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }


    @Test
    void send(){
        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(connectionsCollection);


            setStaticField(Middleware.class, "mongoClient", mockMongoClient);
            setStaticField(Middleware.class, "database", mockDatabase);
            setStaticField(Middleware.class, "usersCollection", usersCollection);
            setStaticField(Middleware.class, "connectionsCollection", connectionsCollection);



        } catch (Exception e) {
            e.printStackTrace();
        }

        try (MockedStatic<LambdaClient> client = Mockito.mockStatic(LambdaClient.class)) {
            setStaticField(MessageRouter.class, "lambdaClient", lambdaClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
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

        event.setBody("{ \"action\": \"send\" }");

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

        InvokeResponse invokeResponse = InvokeResponse.builder()
                .statusCode(200)
                .payload(SdkBytes.fromUtf8String("test"))
                .build();

        lenient().when(lambdaClient.invoke(any(InvokeRequest.class))).thenReturn(invokeResponse);
        lenient().when(lambdaClient.invoke(any(InvokeRequest.class))).thenAnswer(invocation -> {
            InvokeRequest request = invocation.getArgument(0);
            System.out.println("Invoking Lambda function: " + request.functionName());
            return invokeResponse;
        });

        router = new MessageRouter();


        APIGatewayV2WebSocketResponse response = router.handleRequest(event, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Message routed to"));
        verify(lambdaClient).invoke(any(InvokeRequest.class));

    }


//    @Test
//    void heartbeat(){
//
//    }

}