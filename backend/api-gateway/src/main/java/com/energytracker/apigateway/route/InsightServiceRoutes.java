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
public class InsightServiceRoutes {

        @Bean
        public RouterFunction<ServerResponse> insightRoute() {
                return route("insight-service")
                                .route(
                                                RequestPredicates.path("/api/v1/insight/**"),
                                                http())
                                .before(uri("http://localhost:8105"))
                                .filter(circuitBreaker("insight-service", "/fallback/insight-service"))
                                .build();
        }
}
