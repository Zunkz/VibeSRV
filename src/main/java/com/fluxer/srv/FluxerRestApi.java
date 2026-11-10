package com.fluxer.srv;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;

public class FluxerRestApi {
    private static final String BASE_URL = "https://api.fluxer.app/v1";
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json");
    private final String token;
    private final OkHttpClient httpClient;

    /** A channel webhook: id, secret token and the full URL it is executed at. */
    public static final class Webhook {
        public final String id;
        public final String token;
        public final String url;

        public Webhook(String id, String token, String url) {
            this.id = id;
            this.token = token;
            this.url = url;
        }
    }

    private static Webhook webhookOf(String id, String token) {
        return new Webhook(id, token, BASE_URL + "/webhooks/" + id + "/" + token);
    }

    public FluxerRestApi(String token, OkHttpClient httpClient) {
        this.token = token;
        this.httpClient = httpClient;
    }

    public void sendMessage(String channelId, String content, boolean announcement) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if (announcement) {
            body.addProperty("announcement", true);
        }

        post("/channels/" + channelId + "/messages", body);
    }

    public void sendMessage(String channelId, String content) throws IOException {
        sendMessage(channelId, content, false);
    }

    /**
     * Finds a webhook with the given name in the channel, or creates one.
     * Requires the bot to have the "Manage Webhooks" permission.
     */
    public Webhook findOrCreateWebhook(String channelId, String name) throws IOException {
        String route = "/channels/" + channelId + "/webhooks";

        Request listRequest = new Request.Builder()
                .url(BASE_URL + route)
                .header("Authorization", "Bot " + token)
                .get()
                .build();
        JsonElement existing = execute(listRequest);
        if (existing != null && existing.isJsonArray()) {
            for (JsonElement element : existing.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject hook = element.getAsJsonObject();
                if (hook.has("name") && !hook.get("name").isJsonNull()
                        && name.equals(hook.get("name").getAsString())
                        && hook.has("id") && hook.has("token") && !hook.get("token").isJsonNull()) {
                    return webhookOf(hook.get("id").getAsString(), hook.get("token").getAsString());
                }
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        Request createRequest = new Request.Builder()
                .url(BASE_URL + route)
                .header("Authorization", "Bot " + token)
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        JsonElement created = execute(createRequest);
        if (created == null || !created.isJsonObject() || !created.getAsJsonObject().has("token")) {
            throw new IOException("Unexpected response while creating webhook");
        }
        JsonObject hook = created.getAsJsonObject();
        return webhookOf(hook.get("id").getAsString(), hook.get("token").getAsString());
    }

    /**
     * Posts a message through a webhook, overriding the display name and avatar.
     * Webhook execution is authorised by the webhook token, so no bot header is sent.
     */
    public void executeWebhook(Webhook webhook, String content, String username, String avatarUrl)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if (username != null && !username.isEmpty()) {
            body.addProperty("username", username);
        }
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            body.addProperty("avatar_url", avatarUrl);
        }

        postUrl(webhook.url, body, false);
    }

    /** Returns role id -> role name for a guild (used to render role mentions). */
    public java.util.Map<String, String> getGuildRoles(String guildId) throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/guilds/" + guildId + "/roles")
                .header("Authorization", "Bot " + token)
                .get()
                .build();
        JsonElement roles = execute(request);
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (roles != null && roles.isJsonArray()) {
            for (JsonElement element : roles.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject role = element.getAsJsonObject();
                if (role.has("id") && role.has("name") && !role.get("name").isJsonNull()) {
                    names.put(role.get("id").getAsString(), role.get("name").getAsString());
                }
            }
        }
        return names;
    }

    private void post(String route, JsonObject body) throws IOException {
        post(route, body, true);
    }

    private void post(String route, JsonObject body, boolean authorize) throws IOException {
        postUrl(BASE_URL + route, body, authorize);
    }

    private void postUrl(String url, JsonObject body, boolean authorize) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, body.toString()));

        if (authorize) {
            builder.header("Authorization", "Bot " + token);
        }

        execute(builder.build());
    }

    /** Runs a request, throws on non-2xx, and returns the parsed JSON body (or null if empty). */
    private JsonElement execute(Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                // Don't echo the request URL: webhook URLs contain a secret token.
                throw new IOException("Unexpected code " + response.code() + " | Body: "
                        + (responseBody.isEmpty() ? "null" : responseBody));
            }
            if (responseBody.isEmpty()) {
                return null;
            }
            try {
                return GSON.fromJson(responseBody, JsonElement.class);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
