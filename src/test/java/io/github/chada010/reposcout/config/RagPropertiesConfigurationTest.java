package io.github.chada010.reposcout.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagPropertiesConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                            "application", new ClassPathResource("application.yml"))) {
                        context.getEnvironment().getPropertySources().addLast(source);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("无法加载 application.yml", exception);
                }
            })
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void defaultMinScoreComesFromApplicationConfig() {
        contextRunner.run(context -> assertEquals(0.75,
                context.getBean(RagProperties.class).minScore()));
    }

    @Test
    void explicitEnvironmentPropertyOverridesDefault() {
        contextRunner.withPropertyValues("RAG_MIN_SCORE=0.82").run(context -> assertEquals(0.82,
                context.getBean(RagProperties.class).minScore()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagProperties.class)
    static class BindingConfiguration {
    }
}
