package com.amazon.pay.impl;

import com.amazon.pay.exceptions.AmazonClientException;
import com.amazon.pay.types.Environment;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SimpleTimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class V2Util {

    public static final String JAVA_VERSION = System.getProperty("java.version");
    public static final String OS_NAME = System.getProperty("os.name");
    public static final String OS_VERSION = System.getProperty("os.version");
    public static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * Regex which matches any of the sequences that we need to fix up after
     * URLEncoder.encode().
     */
    private static final Pattern ENCODED_CHARACTERS_PATTERN;

    static {
        StringBuilder pattern = new StringBuilder();

        pattern
                .append(Pattern.quote("+"))
                .append("|")
                .append(Pattern.quote("*"))
                .append("|")
                .append(Pattern.quote("%7E"))
                .append("|")
                .append(Pattern.quote("%2F"));

        ENCODED_CHARACTERS_PATTERN = Pattern.compile(pattern.toString());
    }

    /**
     * Generates a url encoded string from the given string
     * @param value the string to be encoded
     * @param path to determine if the given value is a string or not
     * @return the url encoded string
     * @throws AmazonClientException When an error response is returned by Amazon Pay due to bad request or other issue
     */
    public static String urlEncode(String value, final boolean path) throws AmazonClientException {
        if (value == null) {
            return "";
        }

        try {
            if (path) {
                value = value.replaceAll("/+", "/");
            }
            final String encoded = URLEncoder.encode(value, DEFAULT_ENCODING);

            final Matcher matcher = ENCODED_CHARACTERS_PATTERN.matcher(encoded);
            final StringBuffer buffer = new StringBuffer(encoded.length());

            while (matcher.find()) {
                String replacement = matcher.group(0);

                if ("+".equals(replacement)) {
                    replacement = "%20";
                } else if ("*".equals(replacement)) {
                    replacement = "%2A";
                } else if ("%7E".equals(replacement)) {
                    replacement = "~";
                } else if (path && "%2F".equals(replacement)) {
                    replacement = "/";
                }

                matcher.appendReplacement(buffer, replacement);
            }

            matcher.appendTail(buffer);
            return buffer.toString();

        } catch (UnsupportedEncodingException ex) {
            throw new AmazonClientException("Encountered UnsupportedEncodingException:", ex);
        }
    }

    /**
     * Generates a lower case string from the given string
     * @param str the string to be converted
     * @return the lower case string
     */
    public static String lowerCase(final String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }

        return str.toLowerCase(Locale.ENGLISH);
    }

    /**
     * Generates the current time stamp in "yyyyMMdd'T'HHmmss'Z'" format
     * @return the formatted timestamp
     */
    public static String getFormattedTimestamp() {
        final Date now = new Date();
        final SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
                "yyyyMMdd'T'HHmmss'Z'");
        dateTimeFormat.setTimeZone(new SimpleTimeZone(0, "UTC"));
        return dateTimeFormat.format(now);
    }

    /**
     * Builds the PrivateKey object from the private key provided
     * @param char[] privateKey the private key provided
     * @return the PrivateKey object
     * @throws AmazonClientException When an error response is returned by Amazon Pay due to bad request or other issue
     */
    public static PrivateKey buildPrivateKey(final char[] privateKey) throws AmazonClientException {
        Security.addProvider(new BouncyCastleProvider());
        if (privateKey == null || privateKey.length == 0) {
            throw new AmazonClientException("Private key char array cannot be null or empty");
        }
        final PemObject pemObject = getPEMObjectFromKey(privateKey);
        if (pemObject == null) {
            throw new AmazonClientException("Private key string provided is not valid");
        }

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pemObject.getContent());

        PrivateKey privateKeyObject = null;
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            privateKeyObject = keyFactory.generatePrivate(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new AmazonClientException(e.getMessage(), e);
        }

        return privateKeyObject;
    }

    /**
     * To read the contents of the private key
     * @param char[] privateKey the private key provided
     * @return private key pem object
     * @throws AmazonClientException When an error response is returned by Amazon Pay due to bad request or other issue
     */
    private static PemObject getPEMObjectFromKey(final char[] privateKey) throws AmazonClientException {
        PemObject pemObject;
        try {
            final PemReader pemReader = new PemReader(new CharArrayReader(privateKey));
            pemObject = pemReader.readPemObject();
            pemReader.close();

        } catch (IOException e) {
            throw new AmazonClientException(e.getMessage(), e);
        }

        return pemObject;
    }

    /**
     * To get the service URI
     * @param payConfiguration the PayConfiguration object
     * @param action the action to be performed by the request
     * @return the service URI
     * @throws AmazonClientException When an error response is returned by Amazon Pay due to bad request or other issue
     */
    public static URI getServiceURI(PayConfiguration payConfiguration, String action) throws AmazonClientException {
        URI uri;
        try {
            String endpoint = ServiceConstants.endpointMappings.get(payConfiguration.getRegion());
            if (payConfiguration.getOverrideServiceURL() != null) {
                endpoint = "https://" + payConfiguration.getOverrideServiceURL();
            } else {
                endpoint = ServiceConstants.endpointMappings.get(payConfiguration.getRegion());
            }
            uri = new URI(endpoint + getServiceVersionName(payConfiguration, action));
        } catch (URISyntaxException e) {
            throw new AmazonClientException(e.getMessage(), e);
        }
        return uri;
    }

    /**
     * To get the service version name
     * @param payConfiguration the PayConfiguration object
     * @param action the action to be performed by the request
     * @return the service version name
     */
    private static String getServiceVersionName(PayConfiguration payConfiguration, String action) {
        String serviceVersionName = StringUtils.EMPTY;
        if(serviceSupportsEnvPublicKeyId(payConfiguration)) {
            serviceVersionName = "/" + action;
        } else {
            if (payConfiguration.getEnvironment() == Environment.SANDBOX) {
                serviceVersionName = "/" + "sandbox" + "/" + action;
            } else {
                serviceVersionName = "/" + "live" + "/" + action;
            }
        }
        return serviceVersionName;
    }

    private static boolean serviceSupportsEnvPublicKeyId(PayConfiguration payConfiguration) {
        return payConfiguration.getPublicKeyId().toUpperCase(Locale.ROOT).startsWith("LIVE") ||
                payConfiguration.getPublicKeyId().toUpperCase(Locale.ROOT).startsWith("SANDBOX");
    }

    /**
     * Returns the next wait interval, in milliseconds, using an exponential
     * backoff algorithm.
     * @param retryCount The current retry count
     * @return the wait time
     */
    public static long getExponentialWaitTime(int retryCount) {
        return ((long) Math.pow(2, retryCount) * 1000L);
    }

    /**
     * Returns the header with idempotency key, if not provided by the Merchant.
     * Used for Webstore POST requests.
     * @param header Header sent by the merchant
     * @return header Header with Idempotency key
     */
    public static Map<String, String> updateHeader(Map<String, String> header) {
        if (header == null || header.isEmpty()) {
            final Map<String, String> headerMap = new HashMap<String, String>();
            headerMap.put("x-amz-pay-idempotency-key", UUID.randomUUID().toString().replace("-", ""));
            return headerMap;
        }

        boolean isIdempotencyKeyPresent = false;
        for (Map.Entry<String, String> entry : header.entrySet()) {
            if (entry.getKey().toLowerCase().equals("x-amz-pay-idempotency-key")) {
                isIdempotencyKeyPresent = true;
                break;
            }
        }

        if (!isIdempotencyKeyPresent) {
            header.put("x-amz-pay-idempotency-key", UUID.randomUUID().toString().replace("-", ""));
        }

        return header;
    }

    /**
     * Returns the HttpUriRequest object based on the given HTTP Method Name and URI specification.
     *
     * @param httpMethodName the HTTP method
     * @param uri the URI
     * @return the Commons HttpMethodBase object
     * @throws UnsupportedEncodingException
     * @throws AmazonClientException
     */
    public static HttpUriRequest getHttpUriRequest(final URI uri, final String httpMethodName, final String payload)
            throws AmazonClientException, UnsupportedEncodingException {
        switch (httpMethodName) {
            case "GET":
                return new HttpGet(uri);
            case "POST":
                final HttpPost httpPost = new HttpPost(uri);
                httpPost.setEntity(new StringEntity(payload, DEFAULT_ENCODING));
                return httpPost;
            case "PUT":
                final HttpPut httpPut = new HttpPut(uri);
                httpPut.setEntity(new StringEntity(payload, DEFAULT_ENCODING));
                return httpPut;
            case "PATCH":
                final HttpPatch httpPatch = new HttpPatch(uri);
                httpPatch.setEntity(new StringEntity(payload, DEFAULT_ENCODING));
                return httpPatch;
            case "HEAD":
                return new HttpHead(uri);
            case "DELETE":
                final HttpDeleteWithBody httpDeleteWithBody = new HttpDeleteWithBody(uri);
                httpDeleteWithBody.setEntity(new StringEntity(payload, DEFAULT_ENCODING));
                return httpDeleteWithBody;
            case "OPTIONS":
                return new HttpOptions(uri);
            case "TRACE":
                return new HttpTrace(uri);
            default:
                throw new AmazonClientException("Invalid HTTP method " + httpMethodName);
        }
    }

    /**
     * Returns the CloseableHttpClient object based on the given proxy settings.
     *
     * @param proxySettings the ProxySettings
     * @return the CloseableHttpClient
     */
    public static CloseableHttpClient getCloseableHttpClientWithProxy(final ProxySettings proxySettings) {
        final HttpHost proxy = new HttpHost(proxySettings.getProxyHost(),
                proxySettings.getProxyPort());
        final Credentials credentials = new UsernamePasswordCredentials(proxySettings.getProxyUser(),
                String.valueOf(proxySettings.getProxyPassword()));
        final AuthScope authScope = new AuthScope(proxySettings.getProxyHost(),
                proxySettings.getProxyPort());
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(authScope, credentials);
        final HttpClientBuilder httpClientBuilder = HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider)
                .setProxy(proxy);
        return httpClientBuilder.build();
    }
}
