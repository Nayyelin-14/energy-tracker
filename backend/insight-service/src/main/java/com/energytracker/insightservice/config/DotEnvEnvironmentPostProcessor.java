package com.energytracker.insightservice.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Loads the repository root {@code .env} file into the Spring Environment before
 * configuration placeholders such as {@code ${GEMINI_API_KEY:}} are resolved, so the
 * service starts with {@code mvn spring-boot:run} without sourcing the file manually.
 *
 * <p>
 * The file is found by walking up from the working directory (repository root and
 * module directory both work), or from the explicit {@code ENERGY_TRACKER_ENV_FILE}
 * override (system property or environment variable); an override that points to a
 * missing file disables loading entirely. The property source is inserted at the
 * lowest precedence, so shell, CI and Docker environment variables always override
 * values from {@code .env}, and a missing file is ignored. Only file paths and
 * variable counts are ever logged, never variable values.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

        public static final String ENV_FILE_OVERRIDE_PROPERTY = "ENERGY_TRACKER_ENV_FILE";

        static final String PROPERTY_SOURCE_NAME = "energyTrackerDotEnv";
        static final String ENV_FILE_NAME = ".env";

        private static final String DEFAULT_PROPERTIES_SOURCE = "defaultProperties";
        private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        private final org.apache.commons.logging.Log logger;

        public DotEnvEnvironmentPostProcessor(DeferredLogFactory deferredLogFactory) {
                this.logger = deferredLogFactory.getLog(DotEnvEnvironmentPostProcessor.class);
        }

        @Override
        public int getOrder() {
                return ConfigDataEnvironmentPostProcessor.ORDER + 10;
        }

        @Override
        public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
                Optional<Path> envFile = locateEnvFile();
                if (envFile.isEmpty()) {
                        logger.debug("No .env file found; skipping dot-env loading");
                        return;
                }
                Map<String, String> entries = parse(envFile.get());
                if (entries.isEmpty()) {
                        return;
                }
                MutablePropertySources sources = environment.getPropertySources();
                if (sources.contains(PROPERTY_SOURCE_NAME)) {
                        return;
                }
                MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME,
                                new LinkedHashMap<String, Object>(entries));
                if (sources.contains(DEFAULT_PROPERTIES_SOURCE)) {
                        sources.addBefore(DEFAULT_PROPERTIES_SOURCE, source);
                } else {
                        sources.addLast(source);
                }
                logger.info("Loaded " + entries.size() + " variables from "
                                + envFile.get().toAbsolutePath());
        }

        Optional<Path> locateEnvFile() {
                String override = firstNonBlank(
                                System.getProperty(ENV_FILE_OVERRIDE_PROPERTY),
                                System.getenv(ENV_FILE_OVERRIDE_PROPERTY));
                if (override != null) {
                        Path path = Path.of(override);
                        if (!path.isAbsolute()) {
                                path = workingDirectory().resolve(path);
                        }
                        if (Files.isRegularFile(path) && Files.isReadable(path)) {
                                return Optional.of(path.normalize());
                        }
                        logger.debug("Configured env file '" + path.toAbsolutePath()
                                        + "' does not exist; skipping dot-env loading");
                        return Optional.empty();
                }
                return findInAncestors(workingDirectory());
        }

        Optional<Path> findInAncestors(Path start) {
                Path current = start;
                while (current != null) {
                        Path candidate = current.resolve(ENV_FILE_NAME);
                        if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                                return Optional.of(candidate);
                        }
                        current = current.getParent();
                }
                return Optional.empty();
        }

        Map<String, String> parse(Path file) {
                Map<String, String> entries = new LinkedHashMap<>();
                List<String> lines;
                try {
                        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                        logger.warn("Could not read env file " + file.toAbsolutePath()
                                        + ": " + e.getMessage());
                        return entries;
                }
                for (String rawLine : lines) {
                        String line = rawLine.replace("\uFEFF", "").trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                                continue;
                        }
                        if (line.startsWith("export ")) {
                                line = line.substring("export ".length()).trim();
                        }
                        int separator = line.indexOf('=');
                        if (separator <= 0) {
                                continue;
                        }
                        String key = line.substring(0, separator).trim();
                        if (!KEY_PATTERN.matcher(key).matches()) {
                                continue;
                        }
                        entries.put(key, unquote(line.substring(separator + 1).trim()));
                }
                return entries;
        }

        private Path workingDirectory() {
                return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }

        private static String firstNonBlank(String first, String second) {
                if (first != null && !first.isBlank()) {
                        return first.trim();
                }
                if (second != null && !second.isBlank()) {
                        return second.trim();
                }
                return null;
        }

        private static String unquote(String value) {
                if (value.length() >= 2) {
                        char first = value.charAt(0);
                        char last = value.charAt(value.length() - 1);
                        if (first == '"' && last == '"') {
                                return unescape(value.substring(1, value.length() - 1));
                        }
                        if (first == '\'' && last == '\'') {
                                return value.substring(1, value.length() - 1);
                        }
                }
                return value;
        }

        private static String unescape(String value) {
                StringBuilder result = new StringBuilder(value.length());
                int i = 0;
                while (i < value.length()) {
                        char current = value.charAt(i);
                        if (current != '\\' || i + 1 >= value.length()) {
                                result.append(current);
                                i++;
                                continue;
                        }
                        char next = value.charAt(i + 1);
                        switch (next) {
                                case 'n' -> result.append('\n');
                                case 'r' -> result.append('\r');
                                case 't' -> result.append('\t');
                                default -> result.append(next);
                        }
                        i += 2;
                }
                return result.toString();
        }
}