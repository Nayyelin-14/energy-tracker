package com.energytracker.insightservice.service;

import org.springframework.http.HttpStatus;

import com.energytracker.insightservice.config.ResolvedProvider;
import com.energytracker.insightservice.exception.InsightProviderException;

/**
 * Maps upstream SDK failures to safe, non-leaking API errors.
 *
 * <ul>
 * <li>429 → 429 (rate limit; user may switch provider)</li>
 * <li>400/404 → 400 (model likely unavailable upstream)</li>
 * <li>401/403 → 503 (provider configuration problem)</li>
 * <li>everything else → 503</li>
 * </ul>
 */
public final class InsightErrorTranslator {

        private InsightErrorTranslator() {
        }

        public static InsightProviderException translate(Exception e, ResolvedProvider provider) {
                Integer statusCode = findUpstreamStatus(e);
                String providerName = provider.displayName();

                if (statusCode != null && statusCode == 429) {
                        return new InsightProviderException(HttpStatus.TOO_MANY_REQUESTS,
                                        providerName + " rate limit reached. Try again shortly or "
                                                        + "switch to another provider.");
                }
                if (statusCode != null && (statusCode == 400 || statusCode == 404)) {
                        return new InsightProviderException(HttpStatus.BAD_REQUEST,
                                        providerName + " rejected model '" + provider.modelId()
                                                        + "' (" + statusCode + "): " + safeDetail(e)
                                                        + ". The model may be unavailable; choose another model.");
                }
                if (statusCode != null && (statusCode == 401 || statusCode == 403)) {
                        return new InsightProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                                        providerName + " request failed (" + statusCode
                                                        + "). Check the provider configuration on the server.");
                }
                return new InsightProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                                providerName + " request failed: " + safeDetail(e));
        }

        private static Integer findUpstreamStatus(Throwable throwable) {
                Throwable current = throwable;
                while (current != null) {
                        Integer status = upstreamStatus(current);
                        if (status != null) {
                                return status;
                        }
                        current = current.getCause();
                }
                return null;
        }

        private static Integer upstreamStatus(Throwable throwable) {
                if (throwable instanceof com.openai.errors.UnexpectedStatusCodeException openAi) {
                        return openAi.statusCode();
                }
                if (throwable instanceof com.google.genai.errors.ApiException genAi) {
                        return genAi.code();
                }
                return null;
        }

        private static String safeDetail(Throwable throwable) {
                Throwable current = throwable;
                String message = null;
                while (current != null) {
                        if (current.getMessage() != null && !current.getMessage().isBlank()) {
                                message = current.getMessage();
                        }
                        current = current.getCause();
                }
                if (message == null) {
                        return "no details available";
                }
                String singleLine = message.replaceAll("\\s+", " ").trim();
                return singleLine.length() > 200 ? singleLine.substring(0, 200) + "..." : singleLine;
        }
}
