Reading order for backend/insight-service/ (relative to src/main/java/com/energytracker/insightservice/ unless noted):

1. Entry point & wiring
1. pom.xml
1. src/main/resources/application.yaml
1. InsightServiceApplication.java
1. Config layer (config/)
1. InsightProperties.java
1. DotEnvEnvironmentPostProcessor.java
1. RestTemplateConfig.java
1. InsightProviderRegistry.java
1. ResolvedProvider.java
1. AiClientTimeoutConfig.java
1. GenAiLazyInitializationConfig.java
1. InsightCacheConfig.java
1. InsightCache.java
1. HTTP layer (controller/, dto/, exception/)
1. controller/InsightController.java
1. controller/InsightExceptionHandler.java
1. controller/InsightSseStreamer.java
1. dto/InsightType.java
1. dto/InsightDto.java, dto/LlmUsageMetrics.java
1. dto/ProviderCatalogDto.java
1. dto/UsageDto.java, dto/DeviceDto.java, dto/UsageCoverageDto.java
1. Service layer (service/) — mirrors a request's life
1. service/InsightDeadline.java
1. service/InsightPromptAssembler.java
1. service/InsightErrorTranslator.java
1. service/ChunkAccumulator.java
1. service/GenerationSubscriber.java
1. service/InsightGeneration.java ← core state machine
1. service/InsightGenerationCoordinator.java
1. service/InsightGenerationAwaiter.java
1. service/InsightService.java
1. Tests (src/test/)
1. InsightServiceTest → InsightGenerationCoordinatorTest → InsightSseStreamingTest → InsightDeadlineTest → InsightCacheTest → InsightProviderRegistryTest / InsightProviderWiringTests → the rest (env, usage client, boot smoke)
   Also glance at Client/UsageClient.java (the HTTP client used in InsightService.prepare()) — it's the one file the guide's tables don't list.
