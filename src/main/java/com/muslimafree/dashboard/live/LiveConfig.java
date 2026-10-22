package com.muslimafree.dashboard.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * {@code /api/v1/dashboard/live}: one WebSocket per tab or device. The handshake is open (browsers cannot send an
 * Authorization header on a WebSocket); the first frame authenticates. Any origin may connect: the socket carries no
 * cookie or other ambient credential, so a foreign page gains nothing it could not do with a token it already holds.
 */
@Configuration
@EnableWebSocket
public class LiveConfig implements WebSocketConfigurer {

    public static final String PATH = "/api/v1/dashboard/live";

    private final LiveHandler handler;

    public LiveConfig(LiveHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH).setAllowedOriginPatterns("*");
    }
}
