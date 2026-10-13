package com.energytracker.insightservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

class DotEnvEnvironmentPostProcessorTest {

        @TempDir
        Path tempDir;

        private DotEnvEnvironmentPostProcessor processor;
        private String previousUserDir;
        private String previousOverride;

        @BeforeEach
        void setUp() {
                processor = new DotEnvEnvironmentPostProcessor(new DeferredLogs());
                previousUserDir = System.getProperty("user.dir");
                previousOverride = System.getProperty(
                                DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY);
                System.clearProperty(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY);
        }

        @AfterEach
        void restoreSystemProperties() {
                restore("user.dir", previousUserDir);
                restore(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY, previousOverride);
        }

        @Test
        void runsAfterConfigDataAndBeforeDefaultProperties() {
                assertThat(processor.getOrder())
                                .isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
        }

        @Test
        void findsRootEnvFileWhenStartedFromModuleDirectory() throws IOException {
                Path root = tempDir.resolve("energy-tracker");
                Path module = root.resolve("backend").resolve("insight-service");
                Files.createDirectories(module);
                Files.writeString(root.resolve(".env"),
                                "GEMINI_API_KEY=fixture-gemini-key\nGROQ_API_KEY=fixture-groq-key\n");
                System.setProperty("user.dir", module.toString());

                MockEnvironment environment = new MockEnvironment();
                processor.postProcessEnvironment(environment, null);

                assertThat(environment.getProperty("GEMINI_API_KEY"))
                                .isEqualTo("fixture-gemini-key");
                assertThat(environment.getProperty("GROQ_API_KEY"))
                                .isEqualTo("fixture-groq-key");
        }

        @Test
        void findsRootEnvFileWhenStartedFromRepositoryRoot() throws IOException {
                Path root = tempDir.resolve("energy-tracker");
                Files.createDirectories(root);
                Files.writeString(root.resolve(".env"),
                                "GEMINI_API_KEY=fixture-root-key\n");
                System.setProperty("user.dir", root.toString());

                MockEnvironment environment = new MockEnvironment();
                processor.postProcessEnvironment(environment, null);

                assertThat(environment.getProperty("GEMINI_API_KEY"))
                                .isEqualTo("fixture-root-key");
        }

        @Test
        void explicitOverrideWinsOverWalkUp() throws IOException {
                Path root = tempDir.resolve("energy-tracker");
                Path module = root.resolve("backend").resolve("insight-service");
                Files.createDirectories(module);
                Files.writeString(root.resolve(".env"),
                                "GEMINI_API_KEY=fixture-root-key\nONLY_IN_ROOT=fixture-root-only\n");

                Path elsewhere = tempDir.resolve("elsewhere");
                Files.createDirectories(elsewhere);
                Path overrideFile = elsewhere.resolve("custom.env");
                Files.writeString(overrideFile, "GEMINI_API_KEY=fixture-override-key\n");

                System.setProperty("user.dir", module.toString());
                System.setProperty(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY,
                                overrideFile.toString());

                MockEnvironment environment = new MockEnvironment();
                processor.postProcessEnvironment(environment, null);

                assertThat(environment.getProperty("GEMINI_API_KEY"))
                                .isEqualTo("fixture-override-key");
                assertThat(environment.getProperty("ONLY_IN_ROOT")).isNull();
        }

        @Test
        void missingOverrideFileIsIgnoredWithoutFailing() {
                System.setProperty(DotEnvEnvironmentPostProcessor.ENV_FILE_OVERRIDE_PROPERTY,
                                tempDir.resolve("does-not-exist.env").toString());

                MockEnvironment environment = new MockEnvironment();
                assertThatCode(() -> processor.postProcessEnvironment(environment, null))
                                .doesNotThrowAnyException();
                assertThat(environment.getPropertySources()
                                .contains(DotEnvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                                .isFalse();
        }

        @Test
        void missingEnvFileInAncestorsIsIgnoredWithoutFailing() {
                assertThat(processor.findInAncestors(tempDir)).isEmpty();
        }

        @Test
        void parsesQuotedValuesCommentsBlankLinesExportAndCrlf() throws IOException {
                String content = String.join("\r\n",
                                "# comment line",
                                "",
                                "PLAIN=simple-value",
                                "DOUBLE=\"double quoted\"",
                                "SINGLE='single quoted'",
                                "ESCAPES=\"line\\nbreak and \\\"quote\\\"\"",
                                "export EXPORTED=yes",
                                "INVALID LINE WITHOUT SEPARATOR",
                                "1BADKEY=nope",
                                "");
                Path root = tempDir.resolve("energy-tracker");
                Files.createDirectories(root);
                Files.writeString(root.resolve(".env"), content);
                System.setProperty("user.dir", root.toString());

                MockEnvironment environment = new MockEnvironment();
                processor.postProcessEnvironment(environment, null);

                assertThat(environment.getProperty("PLAIN")).isEqualTo("simple-value");
                assertThat(environment.getProperty("DOUBLE")).isEqualTo("double quoted");
                assertThat(environment.getProperty("SINGLE")).isEqualTo("single quoted");
                assertThat(environment.getProperty("ESCAPES"))
                                .isEqualTo("line\nbreak and \"quote\"");
                assertThat(environment.getProperty("EXPORTED")).isEqualTo("yes");
                assertThat(environment.getProperty("INVALID LINE WITHOUT SEPARATOR")).isNull();
                assertThat(environment.getProperty("1BADKEY")).isNull();
        }

        @Test
        void existingEnvironmentValuesOverrideDotFileValues() throws IOException {
                Path root = tempDir.resolve("energy-tracker");
                Files.createDirectories(root);
                Files.writeString(root.resolve(".env"),
                                "GEMINI_API_KEY=fixture-dotenv-key\nGROQ_API_KEY=fixture-new-key\n");
                System.setProperty("user.dir", root.toString());

                MockEnvironment environment = new MockEnvironment()
                                .withProperty("GEMINI_API_KEY", "fixture-existing-key");
                processor.postProcessEnvironment(environment, null);

                assertThat(environment.getProperty("GEMINI_API_KEY"))
                                .isEqualTo("fixture-existing-key");
                assertThat(environment.getProperty("GROQ_API_KEY"))
                                .isEqualTo("fixture-new-key");
        }

        @Test
        void addsDotEnvPropertySourceOnlyOnce() throws IOException {
                Path root = tempDir.resolve("energy-tracker");
                Files.createDirectories(root);
                Files.writeString(root.resolve(".env"), "GEMINI_API_KEY=fixture-gemini-key\n");
                System.setProperty("user.dir", root.toString());

                MockEnvironment environment = new MockEnvironment();
                processor.postProcessEnvironment(environment, null);
                processor.postProcessEnvironment(environment, null);

                assertThat(environment.getPropertySources().stream()
                                .filter(source -> DotEnvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME
                                                .equals(source.getName())))
                                .hasSize(1);
        }

        private static void restore(String key, String previousValue) {
                if (previousValue == null) {
                        System.clearProperty(key);
                } else {
                        System.setProperty(key, previousValue);
                }
        }
}
