package com.energytracker.apigateway.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;

@Configuration
public class DeviceServiceRoutes {

        @Bean
        public RouterFunction<ServerResponse> deviceRoute() {
                return route("device-service")
                                .route(
                                                RequestPredicates.path("/api/v1/device/**"),
                                                http())
                                .before(uri("http://localhost:8101"))
                                .filter(circuitBreaker("device-service", "/fallback/device-service"))
                                .build();
        }
}
