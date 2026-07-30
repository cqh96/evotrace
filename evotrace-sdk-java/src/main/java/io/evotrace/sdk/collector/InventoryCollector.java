package io.evotrace.sdk.collector;

import io.evotrace.protocol.payload.InventoryReportPayload;

/**
 * SPI for collecting a specific category of application inventory.
 * Implementations are auto-discovered by the InventoryReporter.
 */
public interface InventoryCollector {

    /** The category this collector produces (API, DEPENDENCY, CONFIG, etc.) */
    String category();

    /**
     * Collect inventory data. Called on ApplicationReadyEvent.
     * @return populated inventory report, or null if this collector is disabled.
     */
    InventoryReportPayload collect();
}
