package com.payaza.function;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.mongodb.client.*;
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
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(SystemStubsExtension.class)
@ExtendWith(MockitoExtension.class)
class SNSEventProcessorTest {
    private SNSEventProcessor processor;

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
    private FindIterable<Document> transientMessagesCollectionFindIterable;

    @Mock
    MongoCollection<Document> usersCollection;
    @Mock
    MongoCollection<Document> collectionCollection;

    @Mock
    MongoCollection<Document> transientMessagesCollection;
    @Mock
    MongoCollection<Document> connectionsCollection;

    @SystemStub
    private EnvironmentVariables variables =
            new EnvironmentVariables("MONGODB_URI", "mongodb://localhost:27017")
                    .set("MONGODB_DATABASE", "testdb")
                    .set("AWS_REGION", "us-east-1")
                    .set("API_ENDPOINT", "https://api.example.com")
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
    void event_processor() {

        try (MockedStatic<MongoClients> mongoClientsMock = Mockito.mockStatic(MongoClients.class)) {
            mongoClientsMock.when(() -> MongoClients.create(System.getenv("MONGODB_URI"))).thenReturn(mockMongoClient);
            lenient().when(mockMongoClient.getDatabase(anyString())).thenReturn(mockDatabase);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(usersCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(transientMessagesCollection);
            lenient().when(mockDatabase.getCollection(anyString())).thenReturn(connectionsCollection);

            injectField(SNSEventProcessor.class, "mongoClient", mockMongoClient);
            injectField(SNSEventProcessor.class, "database", mockDatabase);
            injectField(SNSEventProcessor.class, "usersCollection", usersCollection);
            injectField(SNSEventProcessor.class, "transientMessagesCollection", transientMessagesCollection);
            injectField(SNSEventProcessor.class, "connectionsCollection", connectionsCollection);



        } catch (Exception e) {
            e.printStackTrace();
        }

        ObjectId id = new ObjectId();

        SNSEvent event = new SNSEvent();
        event.setRecords(List.of(
                new SNSEvent.SNSRecord() {{
                    setEventSource("aws:sns");
                    setSns(new SNSEvent.SNS() {{
                        setMessage("{ \"receiver_id\": \"1234567890\", \"content\": \"Hello, friend!\", \"content_type\": \"text\", \"store\": true, \"ttl\": \"1744516564882\", \"sender_id\": \"" + id + "\" }");
                        setMessageId("test-message-id");
                        setTopicArn("arn:aws:sns:us-east-1:123456789012:MyTopic");
                    }});
                }}
        ));

        Document existingUser = new Document();

        long lastHeartbeat = 1744517420346L;
        existingUser.put("_id", id);
        existingUser.put("username", "testuser");
        existingUser.put("last_heartbeat", lastHeartbeat);
        existingUser.put("password", BCrypt.hashpw("password123", BCrypt.gensalt()));


        when(usersCollection.find(any(Bson.class))).thenReturn(mockFindIterable);
        when(mockFindIterable.first()).thenReturn(existingUser);

        Document existingConnection = new Document();
        existingConnection.put("user_id", id);
        existingConnection.put("connection_id", "test-connection-id");


        when(connectionsCollection.find(any(Bson.class))).thenReturn(collectionmockFindIterable);
        when(collectionmockFindIterable.first()).thenReturn(existingConnection);


        lenient().doNothing().when(transientMessagesCollection).insertOne(any(Document.class));

        processor  = new SNSEventProcessor();

        processor.handleRequest(event, context);

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastHeartbeat > 5000) {
            verify(transientMessagesCollection).insertOne(any(Document.class));
        }

    }
}