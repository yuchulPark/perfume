package com.perfume.search;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
interface ElasticTransport {
    JsonNode request(String method, String path, String body);
}
