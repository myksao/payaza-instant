package com.payaza.function;

import com.amazonaws.lambda.thirdparty.com.google.gson.Gson;
import com.amazonaws.lambda.thirdparty.com.google.gson.JsonObject;
import com.amazonaws.lambda.thirdparty.com.google.gson.JsonParser;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.payaza.utils.APIResponse;
import com.payaza.utils.Middleware;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

public class MessageRouter implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    private final LambdaClient lambdaClient = LambdaClient.create();
    private final Gson gson = new Gson();

    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        String connectionId = event.getRequestContext().getConnectionId();
        String body = event.getBody();

        try {
            Middleware.socket(event, connectionId);

            JsonObject messageJson = JsonParser.parseString(body).getAsJsonObject();
            String action = messageJson.has("action") ? messageJson.get("action").getAsString() : "default";

            return switch (action) {
                case "send" -> routeToHandler("MessageHandlerFunction", event, context);
                // Not implemented yet
                case "typing" -> routeToHandler("TypingHandlerFunction", event, context);
                case "heartbeat" -> routeToHandler("HeartBeatHandlerFunction", event, context);
                default -> APIResponse.socketResponse(400, "Unknown action: " + action);
            };
        } catch (Exception e) {
            context.getLogger().log("Error routing message: " + e.getMessage());
            return APIResponse.socketResponse(500, "Error processing message");
        }

    }

    private APIGatewayV2WebSocketResponse routeToHandler(String functionName, APIGatewayV2WebSocketEvent event, Context context) {
        try {

            InvokeRequest invokeRequest = InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromUtf8String(gson.toJson(event)))
                    .build();

            lambdaClient.invoke(invokeRequest);
            return APIResponse.socketResponse(200, "Message routed to " + functionName);
        } catch (Exception e) {
            context.getLogger().log("Error invoking handler: " + e.getMessage());
            return APIResponse.socketResponse(500, "Error invoking handler");
        }
    }
}
