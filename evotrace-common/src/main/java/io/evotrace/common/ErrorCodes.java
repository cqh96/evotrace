package io.evotrace.common;

public final class ErrorCodes {

    public static final String INVALID_SIGNATURE = "EVO-AUTH-001";
    public static final String CREDENTIAL_EXPIRED = "EVO-AUTH-002";
    public static final String DUPLICATED_EVENT = "EVO-INGEST-001";
    public static final String PAYLOAD_TOO_LARGE = "EVO-INGEST-002";
    public static final String PROJECT_NOT_FOUND = "EVO-BIZ-001";
    public static final String RELEASE_NOT_FOUND = "EVO-BIZ-002";
    public static final String AI_QUOTA_EXCEEDED = "EVO-AI-001";

    private ErrorCodes() {
    }
}
