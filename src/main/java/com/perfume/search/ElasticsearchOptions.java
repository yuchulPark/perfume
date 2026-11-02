package com.perfume.search;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/** No credentials in URLs, command arguments, exception messages or generated toString(). */
public final class ElasticsearchOptions {
    static final String PREFIX = "perfume.elasticsearch.";
    final URI uri;
    final String alias;
    final String apiKey;
    final Path ca;

    private ElasticsearchOptions(URI uri, String alias, String apiKey, Path ca) {
        this.uri = uri; this.alias = alias; this.apiKey = apiKey; this.ca = ca;
    }

    static ElasticsearchOptions from(Environment env, boolean indexing) {
        try {
            var uri = URI.create(env.getRequiredProperty(PREFIX + "url"));
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            String alias = env.getProperty(PREFIX + "alias", "perfumes-search");
            if (!alias.matches("[a-z][a-z0-9-]{0,63}") || alias.contains("-v-")) throw new IllegalArgumentException();
            String key = Files.readString(Path.of(env.getRequiredProperty(PREFIX
                    + (indexing ? "index-api-key-file" : "api-key-file")))).strip();
            if (key.isEmpty() || !key.matches("[A-Za-z0-9+/=_-]+")) throw new IllegalArgumentException();
            Path ca = Path.of(env.getRequiredProperty(PREFIX + "ca-file"));
            return new ElasticsearchOptions(URI.create(uri.getScheme() + "://" + uri.getRawAuthority()), alias, key, ca);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid Elasticsearch HTTPS, alias, CA or API key file configuration");
        }
    }
}
