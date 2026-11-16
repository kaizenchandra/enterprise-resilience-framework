package dev.resilience.democlient;

import dev.resilience.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class DemoClientApplication {
    private final ResilienceExecutor executor;
    private final ResilienceHttpClient client;
    @org.springframework.beans.factory.annotation.Value(
            "${demo.target:http://localhost:8081/orders/quote}")
    String target;

    public DemoClientApplication(ResilienceExecutor executor, ResilienceHttpClient client) {
        this.executor = executor;
        this.client = client;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoClientApplication.class, args);
    }

    @GetMapping("/demo")
    public String call() throws Exception {
        return forward(target);
    }

    @GetMapping("/context")
    public String context() throws Exception {
        return forward(java.net.URI.create(target).resolve("/context").toString());
    }

    private String forward(String destination) throws Exception {
        return executor.execute(
                "order-service.quote",
                RetrySafety.UNSAFE,
                () -> {
                    var request =
                            java.net.http.HttpRequest.newBuilder(java.net.URI.create(destination))
                                    .timeout(java.time.Duration.ofMillis(400))
                                    .GET()
                                    .build();
                    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 429 || response.statusCode() == 503)
                        throw new RetryAfterException(response.statusCode(), java.time.Duration.ofSeconds(1));
                    if (response.statusCode() >= 500) throw new java.io.IOException("Dependency unavailable");
                    if (response.statusCode() >= 400)
                        throw new IllegalArgumentException("Dependency rejected request");
                    return response.body();
                });
    }

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<String> failure(Exception e) {
        System.getLogger(getClass().getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Dependency call failed: {0}",
                        e.getClass().getSimpleName());
        return org.springframework.http.ResponseEntity.status(503)
                .body("Dependency unavailable; no financial action was confirmed");
    }
}
