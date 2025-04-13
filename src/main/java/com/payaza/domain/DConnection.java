package com.payaza.domain;

import com.amazonaws.lambda.thirdparty.com.google.gson.annotations.SerializedName;
import lombok.With;

public record DConnection(
        @With
        @SerializedName("user_id")
        String user_id,
        @With
        @SerializedName("connection_id")
        String connection_id,
        @With
        @SerializedName("connected_at")
        long connected_at,
        @With
        @SerializedName("user_agent")
        String user_agent,
        @With
        @SerializedName("ip_address")
        String ip_address
) {
}
