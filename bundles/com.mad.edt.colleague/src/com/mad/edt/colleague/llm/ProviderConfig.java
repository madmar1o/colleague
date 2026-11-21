package com.mad.edt.colleague.llm;

/**
 * Immutable connection settings of one language model endpoint.
 */
public final class ProviderConfig {

    /** Runtime duration text: a positive whole number glued to a time unit. */
    private static final java.util.regex.Pattern LIFETIME_TEXT =
        java.util.regex.Pattern.compile("(\\d+)(ms|s|m|h)");

    /**
     * Default residence lifetime a local runtime is asked to hold the model
     * loaded after serving a call: long enough to cover the gap between two
     * user questions, short enough to release memory over a real pause.
     */
    public static final String DEFAULT_LOCAL_KEEP_ALIVE = "30m";

    /**
     * Token window a local runtime is asked to keep ready by default: the
     * runtime's own default is small enough to cut a normal question with
     * attached workspace context away without any warning.
     */
    public static final int DEFAULT_LOCAL_CONTEXT_TOKENS = 8_192;

    /**
     * Largest token window the endpoint is ever asked for by default: a wide
     * window is paid with model memory, so a prompt beyond this limit is
     * deliberately left to the runtime's own trimming instead of inflating
     * the cache without bound.
     */
    public static final int MAX_LOCAL_CONTEXT_TOKENS = 16_384;

    private final String id;
    private final LLMProviderType type;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String keepAlive;
    private final int contextWindowTokens;

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
        keepAlive = theBuilder.keepAlive;
        contextWindowTokens = theBuilder.contextWindowTokens;
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
     * @return residence lifetime the local runtime is asked to keep the model
     *         loaded after serving a call, {@code null} or empty when the
     *         endpoint never needs it (gateway-style providers)
     */
    public String getKeepAlive() {
        return keepAlive;
    }

    /**
     * @return token window the local runtime is asked to keep ready for one
     *         call, {@code 0} or less when the option is never sent
     *         (gateway-style endpoints or an explicit opt-out)
     */
    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    /**
     * The bound a request-sized widening of the window may never pass: the
     * configured window itself when it is already roomier than the default
     * ceiling.
     *
     * @return ceiling in tokens
     */
    public int getContextWindowCeiling() {
        return Math.max(MAX_LOCAL_CONTEXT_TOKENS, contextWindowTokens);
    }

    /**
     * @return the residence claim as milliseconds, {@code -1} when there is
     *         no claim, the claim is forever, or the lifetime text is not a
     *         simple duration a renewal timer could understand
     */
    public long keepAliveLifetimeMs() {
        if(keepAlive == null || keepAlive.isEmpty()) {
            return -1;
        }
        final java.util.regex.Matcher duration = LIFETIME_TEXT.matcher(keepAlive);
        if(!duration.matches()) {
            return -1;
        }
        final long amount = Long.parseLong(duration.group(1));
        if(amount <= 0) {
            return -1;
        }
        switch(duration.group(2)) {
            case "ms":
                return amount;
            case "s":
                return amount * 1_000;
            case "m":
                return amount * 60_000;
            case "h":
                return amount * 3_600_000;
            default:
                return -1;
        }
    }

    /**
     * How often a loaded model should be asked to renew its residence claim
     * so it never goes cold while the user keeps coming back.
     *
     * @return delay in milliseconds, {@code -1} when nothing ever needs
     *         renewing (gateway-style or non-expiring endpoints)
     */
    public long warmRenewalDelayMs() {
        final long lifetime = keepAliveLifetimeMs();
        // renew early enough that the claim never lapses between two visits
        return lifetime <= 0 ? -1 : Math.max(60_000, lifetime / 3);
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
        private String keepAlive;
        private int contextWindowTokens;

        private Builder(final String theId, final LLMProviderType theType) {
            id = theId;
            type = theType;
            // only a runtime on the same machine pays the cold model load.
            // Gateway-style endpoints start warm and ignore the claim
            keepAlive = theType == LLMProviderType.OLLAMA
                ? DEFAULT_LOCAL_KEEP_ALIVE : null;
            // only a local runtime silently trims the prompt to its own tiny
            // default window; gateways take the whole message list as sent
            contextWindowTokens = theType == LLMProviderType.OLLAMA
                ? DEFAULT_LOCAL_CONTEXT_TOKENS : 0;
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
         * Overrides how long a local runtime should hold the model loaded
         * after serving a call; {@code null} or empty never claims residence.
         *
         * @param theKeepAlive lifetime text understood by the runtime
         * @return this builder
         */
        public Builder keepAlive(final String theKeepAlive) {
            keepAlive = theKeepAlive;
            return this;
        }

        /**
         * Overrides the token window a local runtime is asked to keep ready
         * for one call; {@code 0} or less never sends the option.
         *
         * @param theContextWindowTokens window in tokens
         * @return this builder
         */
        public Builder contextWindowTokens(final int theContextWindowTokens) {
            contextWindowTokens = theContextWindowTokens;
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
