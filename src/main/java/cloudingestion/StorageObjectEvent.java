package cloudingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of the Eventarc event "google.cloud.storage.object.v1.finalized"
 * (binary content mode: the HTTP body contains the storage object metadata).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageObjectEvent(String bucket, String name) {
}
