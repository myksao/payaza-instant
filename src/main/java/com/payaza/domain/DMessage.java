package com.payaza.domain;


import com.amazonaws.lambda.thirdparty.com.google.gson.annotations.SerializedName;
import lombok.With;

public record DMessage(
        @With
        @SerializedName("sender_id")
        String senderId,
        @With
        @SerializedName("receiver_id")
        String receiverId,
        @With
        @SerializedName("connection_id")
        String connectionId,
        @With
        @SerializedName("content")
        String content,
        @With
        @SerializedName("timestamp")
        long timestamp,
        @With
        @SerializedName("content_type")
        String contentType,
        @With
        @SerializedName("is_read")
        boolean isRead,
        @With
        @SerializedName("store")
        boolean store,
        @With
        @SerializedName("ttl")
        long ttl
) {
}
