package com.energytracker.insightservice.service;

import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.service.InsightService.CachedInsight;

/**
 * Observer of one shared generation. Terminal callbacks
 * ({@link #onComplete}/{@link #onError}) fire at most once per subscriber.
 */
public interface GenerationSubscriber {

        void onDelta(String text);

        void onComplete(CachedInsight result);

        void onError(InsightProviderException error);
}
