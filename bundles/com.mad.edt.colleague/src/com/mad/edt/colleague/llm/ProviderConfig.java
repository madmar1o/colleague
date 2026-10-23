package com.mad.edt.colleague.llm;

/**
 * Immutable connection settings of one language model endpoint.
 */
public final class ProviderConfig {

    private final String id;
    private final LLMProviderType type;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private ProviderConfig(final Builder theBuilder) {
        if(theBuilder.id == null || theBuilder.id.isEmpty()) {
            throw new IllegalArgumentException("provider id is empty");
        }
        if(theBuilder.baseUrl == null || theBuilder.baseUrl.isEmpty()) {
            throw new IllegalArgumentException("provider base URL is empty");
        }
        id = theBuilder.id;
        type = theBuilder.type;
        baseUrl = stripTrailingSlash(theBuilder.baseUrl);
        apiKey = theBuilder.apiKey;
        defaultModel = theBuilder.defaultModel;
        connectTimeoutMs = theBuilder.connectTimeoutMs;
        readTimeoutMs = theBuilder.readTimeoutMs;
    }

    /**
     * @return stable identifier used in settings and logs
     */
    public String getId() {
        return id;
    }

    /**
     * @return endpoint protocol
     */
    public LLMProviderType getType() {
        return type;
    }

    /**
     * @return absolute base URL of the API without a trailing slash
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * @return bearer token for remote endpoints, {@code null} for unauthenticated ones
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * @return model used when a request does not name one, may be {@code null}
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * @return TCP connect timeout in milliseconds
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * @return response read timeout in milliseconds
     */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @param theId provider identifier
     * @return a new builder
     */
    public static Builder builder(final String theId, final LLMProviderType theType) {
        return new Builder(theId, theType);
    }

    /**
     * Builder for {@link ProviderConfig}.
     */
    public static final class Builder {

        private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
        private static final int DEFAULT_READ_TIMEOUT_MS = 180_000;

        private final String id;
        private final LLMProviderType type;
        private String baseUrl;
        private String apiKey;
        private String defaultModel;
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

        private Builder(final String theId, final LLMProviderType theType) {
            id = theId;
            type = theType;
        }

        /**
         * @param theBaseUrl absolute API base URL
         * @return this builder
         */
        public Builder baseUrl(final String theBaseUrl) {
            baseUrl = theBaseUrl;
            return this;
        }

        /**
         * @param theApiKey bearer token, {@code null} to omit authentication
         * @return this builder
         */
        public Builder apiKey(final String theApiKey) {
            apiKey = theApiKey;
            return this;
        }

        /**
         * @param theModel default model name
         * @return this builder
         */
        public Builder defaultModel(final String theModel) {
            defaultModel = theModel;
            return this;
        }

        /**
         * @param theTimeoutMs connect timeout in milliseconds
         * @return this builder
         */
        public Builder connectTimeoutMs(final int theTimeoutMs) {
            connectTimeoutMs = theTimeoutMs;
            return this;
        }

        /**
         * @param theTimeoutMs read timeout in milliseconds
         * @return this builder
         */
        public Builder readTimeoutMs(final int theTimeoutMs) {
            readTimeoutMs = theTimeoutMs;
            return this;
        }

        /**
         * @return the immutable configuration
         */
        public ProviderConfig build() {
            return new ProviderConfig(this);
        }
    }

    private static String stripTrailingSlash(final String theUrl) {
        String url = theUrl;
        while(url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
