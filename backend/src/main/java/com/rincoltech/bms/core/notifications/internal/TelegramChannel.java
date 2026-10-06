package com.rincoltech.bms.core.notifications.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Operator alerts through the Telegram Bot API {@code sendMessage} over HTTPS, with the JDK HTTP
 * client (spec section 11). The only recipient is the operator chat of
 * {@code BMS_TELEGRAM_OPERATOR_CHAT_ID}; the bot token is part of the URL and so never appears
 * in an error message or a log line.
 */
@Component
class TelegramChannel implements OutboxChannel {

    private static final String API = "https://api.telegram.org/bot";

    private final NotificationProperties.Telegram telegram;
    private final ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    TelegramChannel(NotificationProperties properties, ObjectMapper mapper) {
        this.telegram = properties.telegram();
        this.mapper = mapper;
    }

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public boolean enabled() {
        return telegram.configured();
    }

    @Override
    public void send(Delivery delivery) throws Exception {
        if (!"operator".equals(delivery.recipient())) {
            throw new IllegalArgumentException("Telegram sends to the operator chat only");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", telegram.operatorChatId());
        body.put("text", delivery.text());
        body.put("disable_web_page_preview", true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + telegram.botToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            // The exception text can name the URL, which carries the token.
            throw new IllegalStateException(
                    "Telegram unreachable: " + e.getClass().getSimpleName());
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Telegram answered HTTP " + response.statusCode());
        }
    }
}
