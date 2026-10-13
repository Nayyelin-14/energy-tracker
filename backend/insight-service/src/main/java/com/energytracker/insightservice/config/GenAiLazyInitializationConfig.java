package com.energytracker.insightservice.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI's Google Gemini auto-configuration eagerly instantiates a
 * {@code com.google.genai.Client}, which fails application start-up whenever no
 * Gemini API key (and no Vertex AI project/location) is configured.
 *
 * <p>
 * This post-processor marks every bean definition contributed by the Gemini
 * auto-configurations as lazy, so a server without a Gemini key starts normally.
 * The beans are only created when {@link InsightProviderRegistry} resolves a
 * keyed Gemini provider, i.e. never unless the provider is actually usable.
 */
@Configuration(proxyBeanMethods = false)
public class GenAiLazyInitializationConfig {

        static final String GENAI_AUTOCONFIGURATION_PACKAGE = "org.springframework.ai.model.google.genai.autoconfigure.";

        @Bean
        static BeanFactoryPostProcessor deferGeminiBeansUntilFirstUse() {
                return GenAiLazyInitializationConfig::makeGeminiBeansLazy;
        }

        private static void makeGeminiBeansLazy(ConfigurableListableBeanFactory beanFactory) {
                for (String name : beanFactory.getBeanDefinitionNames()) {
                        BeanDefinition definition = beanFactory.getBeanDefinition(name);
                        if (isGeminiBean(beanFactory, name, definition)) {
                                definition.setLazyInit(true);
                        }
                }
        }

        private static boolean isGeminiBean(
                        ConfigurableListableBeanFactory beanFactory,
                        String name,
                        BeanDefinition definition) {
                if (name.startsWith("googleGenAi")) {
                        return true;
                }
                String factoryBeanName = definition.getFactoryBeanName();
                if (factoryBeanName == null || !beanFactory.containsBeanDefinition(factoryBeanName)) {
                        return false;
                }
                String factoryClass = beanFactory.getBeanDefinition(factoryBeanName).getBeanClassName();
                return factoryClass != null && factoryClass.startsWith(GENAI_AUTOCONFIGURATION_PACKAGE);
        }
}
