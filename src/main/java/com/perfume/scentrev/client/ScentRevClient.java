package com.perfume.scentrev.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevClientException.FailureType;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** Direct REST retrieval only; never invokes persistence or uses MCP/OAuth credentials. */
public class ScentRevClient {

    private static final List<String> PHASE1_SECTIONS = List.of("identity", "perfumers", "notes", "accords");
    private static final int MAX_ERROR_BODY_BYTES = 8192;
    private static final int MAX_DIAGNOSTIC_LENGTH = 512;

    private final RestClient restClient;
    private final ScentRevClientProperties properties;
    private final ObjectMapper objectMapper;

    public ScentRevClient(RestClient restClient, ScentRevClientProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches one profile by public ID, requesting only Phase 1 sections without
     * perfumer portfolios. The request section "notes" maps to the existing
     * response field "note_pyramid". No retry or database import is performed.
     */
    public ScentRevFragranceProfileResponse getFragranceProfile(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("ScentRev publicId must not be null or blank");
        }
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ScentRevClientException(FailureType.CONFIGURATION, null,
                    "ScentRev API key is not configured");
        }

        try {
            ResponseEntity<ScentRevFragranceProfileResponse> response = restClient.post()
                    .uri("/rpc/get_fragrance_profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(Map.of("public_id", publicId, "sections", PHASE1_SECTIONS,
                            "include_perfumer_portfolio", false))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, errorResponse) -> {
                        throw translateHttpError(errorResponse, apiKey);
                    })
                    .toEntity(ScentRevFragranceProfileResponse.class);

            ScentRevFragranceProfileResponse profile = response.getBody();
            if (profile == null || profile.identity() == null) {
                throw new ScentRevClientException(FailureType.INVALID_RESPONSE, response.getStatusCode().value(),
                        "ScentRev profile response is empty or missing identity");
            }
            return profile;
        } catch (ResourceAccessException exception) {
            // Raw transport/conversion exceptions can contain provider text; do not retain them as causes.
            throw new ScentRevClientException(FailureType.TRANSPORT, null,
                    "ScentRev profile request could not be completed");
        } catch (RestClientException exception) {
            throw new ScentRevClientException(FailureType.INVALID_RESPONSE, null,
                    "ScentRev profile response could not be read");
        }
    }

    private ScentRevClientException translateHttpError(ClientHttpResponse response, String apiKey) throws IOException {
        int status = response.getStatusCode().value();
        FailureType type;
        String description;
        if (status == 401 || status == 403) {
            type = FailureType.AUTHENTICATION;
            description = "ScentRev authentication failed";
        } else if (status == 429) {
            type = FailureType.RATE_LIMIT;
            description = "ScentRev rate limit exceeded";
        } else if (status >= 400 && status < 500) {
            type = FailureType.INVALID_REQUEST;
            description = "ScentRev rejected the profile request";
        } else if (status >= 500 && status < 600) {
            type = FailureType.SERVER;
            description = "ScentRev server failure";
        } else {
            type = FailureType.INVALID_RESPONSE;
            description = "ScentRev returned an unexpected HTTP status";
        }
        String diagnostic = safeProviderDiagnostic(response, apiKey);
        String message = description + " (HTTP " + status + ")";
        if (diagnostic != null && !diagnostic.isBlank()) {
            message += ": " + diagnostic;
        }
        return new ScentRevClientException(type, status, message);
    }

    private String safeProviderDiagnostic(ClientHttpResponse response, String apiKey) {
        try {
            byte[] body = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES + 1);
            // Discard truncated bodies so a partial credential cannot bypass redaction.
            if (body.length == 0 || body.length > MAX_ERROR_BODY_BYTES) {
                return null;
            }
            String diagnostic;
            try {
                JsonNode document = objectMapper.readTree(body);
                if (document == null) {
                    return null;
                }
                JsonNode message = document.path("message");
                if (!message.isTextual()) {
                    message = document.path("error").path("message");
                }
                if (!message.isTextual()) {
                    message = document.path("detail");
                }
                if (!message.isTextual()) {
                    message = document.path("error");
                }
                diagnostic = message.isTextual() ? message.textValue() : null;
            } catch (IOException exception) {
                MediaType contentType = response.getHeaders().getContentType();
                diagnostic = contentType != null && MediaType.TEXT_PLAIN.isCompatibleWith(contentType)
                        ? new String(body, StandardCharsets.UTF_8) : null;
            }
            if (diagnostic == null) {
                return null;
            }
            String safe = diagnostic.replace(apiKey, "[REDACTED]")
                    .replaceAll("(?i)Bearer\\s+[^\\s\"',;<>]+", "Bearer [REDACTED]")
                    .replaceAll("\\p{Cntrl}", " ").strip();
            return safe.substring(0, Math.min(safe.length(), MAX_DIAGNOSTIC_LENGTH));
        } catch (IOException exception) {
            return null;
        }
    }
}
