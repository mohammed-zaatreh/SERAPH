package com.ttu_elite.seraph.Services;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
public class RedditClient {
    ;
    private final RestClient restClient = RestClient.create();

    @Value("${reddit.clientId}")
    private String clientId;

    @Value("${reddit.clientSecret}")
    private String clientSecret;

    @Value("${reddit.userAgent}")
    private String userAgent;
    /**
     * Exchanges Client ID/Secret for a temporary Access Token.
     */
    public String getAppToken() {
        String authString = clientId + ":" + clientSecret;
        String basicAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        Map response = restClient.post()
                .uri("https://www.reddit.com/api/v1/access_token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("access_token")) {
            throw new RuntimeException("Failed to retrieve Reddit access token");
        }

        return response.get("access_token").toString();
    }

    /**
     * Fetches a page of posts submitted by a specific user.
     */
    public Map<String, Object> fetchUserSubmitted(String token, String username, String after) {
        String url = "https://oauth.reddit.com/user/" + username + "/submitted?limit=100";
        if (after != null && !after.isBlank()) {
            url += "&after=" + after;
        }

        return restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .retrieve()
                .body(Map.class);
    }}
