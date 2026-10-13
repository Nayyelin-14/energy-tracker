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
public class IngestionServiceRoutes {

        @Bean
        public RouterFunction<ServerResponse> ingestionRoute() {
                return route("ingestion-service")
                                .route(
                                                RequestPredicates.path("/api/v1/ingestion/**"),
                                                http())
                                .before(uri("http://localhost:8104"))
                                .filter(circuitBreaker("ingestion-service", "/fallback/ingestion-service"))
                                .build();
        }
}
