package com.payaza.domain;

import com.google.gson.annotations.SerializedName;
import lombok.With;

public record DUser(
        @With
        @SerializedName("username")
        String username,
        @With
        @SerializedName("password")
        String password,
        @With
        @SerializedName("token")
        String token,
        @With
        @SerializedName("ttl")
        long ttl,
        @With
        @SerializedName("last_heartbeat")
        String last_heartbeat
) {
}
