/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.client;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriBuilderFactory;

/**
 * Default implementation of {@link RestClient}.
 *
 * @author Arjen Poutsma
 * @since 6.1
 */
final class DefaultRestClient implements RestClient {

	private static final Log logger = LogFactory.getLog(DefaultRestClient.class);

	private static final String URI_TEMPLATE_ATTRIBUTE = RestClient.class.getName() + ".uriTemplate";


	private final ClientHttpRequestFactory clientRequestFactory;

	@Nullable
	private volatile ClientHttpRequestFactory interceptingRequestFactory;

	@Nullable
	private final List<ClientHttpRequestInitializer> initializers;

	@Nullable
	private final List<ClientHttpRequestInterceptor> interceptors;

	private final UriBuilderFactory uriBuilderFactory;

	@Nullable
	private final HttpHeaders defaultHeaders;

	private final List<StatusHandler> defaultStatusHandlers;

	private final DefaultRestClientBuilder builder;

	private final List<HttpMessageConverter<?>> messageConverters;


	DefaultRestClient(ClientHttpRequestFactory clientRequestFactory, @Nullable List<ClientHttpRequestInterceptor> interceptors,
			@Nullable List<ClientHttpRequestInitializer> initializers,
			UriBuilderFactory uriBuilderFactory,
			@Nullable HttpHeaders defaultHeaders,
			@Nullable List<StatusHandler> statusHandlers,
			List<HttpMessageConverter<?>> messageConverters,
			DefaultRestClientBuilder builder) {

		this.clientRequestFactory = clientRequestFactory;
		this.initializers = initializers;
		this.interceptors = interceptors;
		this.uriBuilderFactory = uriBuilderFactory;
		this.defaultHeaders = defaultHeaders;
		this.defaultStatusHandlers = (statusHandlers != null) ? new ArrayList<>(statusHandlers) : new ArrayList<>();
		this.messageConverters = messageConverters;
		this.builder = builder;
	}

	@Override
	public RequestHeadersUriSpec<?> get() {
		return methodInternal(HttpMethod.GET);
	}

	@Override
	public RequestHeadersUriSpec<?> head() {
		return methodInternal(HttpMethod.HEAD);
	}

	@Override
	public RequestBodyUriSpec post() {
		return methodInternal(HttpMethod.POST);
	}

	@Override
	public RequestBodyUriSpec put() {
		return methodInternal(HttpMethod.PUT);
	}

	@Override
	public RequestBodyUriSpec patch() {
		return methodInternal(HttpMethod.PATCH);
	}

	@Override
	public RequestHeadersUriSpec<?> delete() {
		return methodInternal(HttpMethod.DELETE);
	}

	@Override
	public RequestHeadersUriSpec<?> options() {
		return methodInternal(HttpMethod.OPTIONS);
	}

	@Override
	public RequestBodyUriSpec method(HttpMethod method) {
		Assert.notNull(method, "HttpMethod must not be null");
		return methodInternal(method);
	}

	private RequestBodyUriSpec methodInternal(HttpMethod httpMethod) {
		return new DefaultRequestBodyUriSpec(httpMethod);
	}

	@Override
	public Builder mutate() {
		return new DefaultRestClientBuilder(this.builder);
	}


	private class DefaultRequestBodyUriSpec implements RequestBodyUriSpec {

		private final HttpMethod httpMethod;

		@Nullable
		private URI uri;

		@Nullable
		private HttpHeaders headers;

		@Nullable
		private InternalBody body;

		private final Map<String, Object> attributes = new LinkedHashMap<>(4);

		@Nullable
		private Consumer<ClientHttpRequest> httpRequestConsumer;

		public DefaultRequestBodyUriSpec(HttpMethod httpMethod) {
			this.httpMethod = httpMethod;
		}


		@Override
		public RequestBodySpec uri(String uriTemplate, Object... uriVariables) {
			attribute(URI_TEMPLATE_ATTRIBUTE, uriTemplate);
			return uri(DefaultRestClient.this.uriBuilderFactory.expand(uriTemplate, uriVariables));
		}

		@Override
		public RequestBodySpec uri(String uriTemplate, Map<String, ?> uriVariables) {
			attribute(URI_TEMPLATE_ATTRIBUTE, uriTemplate);
			return uri(DefaultRestClient.this.uriBuilderFactory.expand(uriTemplate, uriVariables));
		}

		@Override
		public RequestBodySpec uri(String uriTemplate, Function<UriBuilder, URI> uriFunction) {
			attribute(URI_TEMPLATE_ATTRIBUTE, uriTemplate);
			return uri(uriFunction.apply(DefaultRestClient.this.uriBuilderFactory.uriString(uriTemplate)));
		}

		@Override
		public RequestBodySpec uri(Function<UriBuilder, URI> uriFunction) {
			return uri(uriFunction.apply(DefaultRestClient.this.uriBuilderFactory.builder()));
		}

		@Override
		public RequestBodySpec uri(URI uri) {
			this.uri = uri;
			return this;
		}

		private HttpHeaders getHeaders() {
			if (this.headers == null) {
				this.headers = new HttpHeaders();
			}
			return this.headers;
		}

		@Override
		public DefaultRequestBodyUriSpec header(String headerName, String... headerValues) {
			for (String headerValue : headerValues) {
				getHeaders().add(headerName, headerValue);
			}
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec headers(Consumer<HttpHeaders> headersConsumer) {
			headersConsumer.accept(getHeaders());
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec accept(MediaType... acceptableMediaTypes) {
			getHeaders().setAccept(Arrays.asList(acceptableMediaTypes));
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec acceptCharset(Charset... acceptableCharsets) {
			getHeaders().setAcceptCharset(Arrays.asList(acceptableCharsets));
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec contentType(MediaType contentType) {
			getHeaders().setContentType(contentType);
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec contentLength(long contentLength) {
			getHeaders().setContentLength(contentLength);
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec ifModifiedSince(ZonedDateTime ifModifiedSince) {
			getHeaders().setIfModifiedSince(ifModifiedSince);
			return this;
		}

		@Override
		public DefaultRequestBodyUriSpec ifNoneMatch(String... ifNoneMatches) {
			getHeaders().setIfNoneMatch(Arrays.asList(ifNoneMatches));
			return this;
		}

		@Override
		public RequestBodySpec attribute(String name, Object value) {
			this.attributes.put(name, value);
			return this;
		}

		@Override
		public RequestBodySpec attributes(Consumer<Map<String, Object>> attributesConsumer) {
			attributesConsumer.accept(this.attributes);
			return this;
		}

		@Override
		public RequestBodySpec httpRequest(Consumer<ClientHttpRequest> requestConsumer) {
			this.httpRequestConsumer = (this.httpRequestConsumer != null ?
					this.httpRequestConsumer.andThen(requestConsumer) : requestConsumer);
			return this;
		}

		@Override
		public RequestBodySpec body(Object body) {
			this.body = clientHttpRequest -> writeWithMessageConverters(body, body.getClass(), clientHttpRequest);
			return this;
		}

		@Override
		public <T> RequestBodySpec body(T body, ParameterizedTypeReference<T> bodyType) {
			this.body = clientHttpRequest -> writeWithMessageConverters(body, bodyType.getType(), clientHttpRequest);
			return this;
		}

		@Override
		public RequestBodySpec body(StreamingHttpOutputMessage.Body body) {
			this.body = request -> body.writeTo(request.getBody());
			return this;
		}

		@SuppressWarnings({"rawtypes", "unchecked"})
		private void writeWithMessageConverters(Object body, Type bodyType, ClientHttpRequest clientRequest)
				throws IOException {

			MediaType contentType = clientRequest.getHeaders().getContentType();
			Class<?> bodyClass = body.getClass();

			for (HttpMessageConverter messageConverter : DefaultRestClient.this.messageConverters) {
				if (messageConverter instanceof GenericHttpMessageConverter genericMessageConverter) {
					if (genericMessageConverter.canWrite(bodyType, bodyClass, contentType)) {
						logBody(body, contentType, genericMessageConverter);
						genericMessageConverter.write(body, bodyType, contentType, clientRequest);
						return;
					}
				}
				if (messageConverter.canWrite(bodyClass, contentType)) {
					logBody(body, contentType, messageConverter);
					messageConverter.write(body, contentType, clientRequest);
					return;
				}
			}
			String message = "No HttpMessageConverter for " + bodyClass.getName();
			if (contentType != null) {
				message += " and content type \"" + contentType + "\"";
			}
			throw new RestClientException(message);
		}

		private void logBody(Object body, @Nullable MediaType mediaType, HttpMessageConverter<?> converter) {
			if (logger.isDebugEnabled()) {
				StringBuilder msg = new StringBuilder("Writing [");
				msg.append(body);
				msg.append("] ");
				if (mediaType != null) {
					msg.append("as \"");
					msg.append(mediaType);
					msg.append("\" ");
				}
				msg.append("with ");
				msg.append(converter.getClass().getName());
				logger.debug(msg.toString());
			}
		}


		@Override
		public ResponseSpec retrieve() {
			return exchangeInternal(DefaultResponseSpec::new, false);
		}

		@Override
		public <T> T exchange(ExchangeFunction<T> exchangeFunction, boolean close) {
			return exchangeInternal(exchangeFunction, close);
		}

		private <T> T exchangeInternal(ExchangeFunction<T> exchangeFunction, boolean close) {
			Assert.notNull(exchangeFunction, "ExchangeFunction must not be null");

			ClientHttpResponse clientResponse = null;
			URI uri = null;
			try {
				uri = initUri();
				HttpHeaders headers = initHeaders();
				ClientHttpRequest clientRequest = createRequest(uri);
				clientRequest.getHeaders().addAll(headers);
				if (this.body != null) {
					this.body.writeTo(clientRequest);
				}
				if (this.httpRequestConsumer != null) {
					this.httpRequestConsumer.accept(clientRequest);
				}
				clientResponse = clientRequest.execute();
				return exchangeFunction.exchange(clientRequest, clientResponse);
			}
			catch (IOException ex) {
				throw createResourceAccessException(uri, this.httpMethod, ex);
			}
			finally {
				if (close && clientResponse != null) {
					clientResponse.close();
				}
			}
		}

		private URI initUri() {
			return (this.uri != null ? this.uri : DefaultRestClient.this.uriBuilderFactory.expand(""));
		}

		private HttpHeaders initHeaders() {
			HttpHeaders defaultHeaders = DefaultRestClient.this.defaultHeaders;
			if (CollectionUtils.isEmpty(this.headers)) {
				return (defaultHeaders != null ? defaultHeaders : new HttpHeaders());
			}
			else if (CollectionUtils.isEmpty(defaultHeaders)) {
				return this.headers;
			}
			else {
				HttpHeaders result = new HttpHeaders();
				result.putAll(defaultHeaders);
				result.putAll(this.headers);
				return result;
			}
		}

		private ClientHttpRequest createRequest(URI uri) throws IOException {
			ClientHttpRequestFactory factory;
			if (DefaultRestClient.this.interceptors != null) {
				factory = DefaultRestClient.this.interceptingRequestFactory;
				if (factory == null) {
					factory = new InterceptingClientHttpRequestFactory(DefaultRestClient.this.clientRequestFactory, DefaultRestClient.this.interceptors);
					DefaultRestClient.this.interceptingRequestFactory = factory;
				}
			}
			else {
				factory = DefaultRestClient.this.clientRequestFactory;
			}
			ClientHttpRequest request = factory.createRequest(uri, this.httpMethod);
			if (DefaultRestClient.this.initializers != null) {
				DefaultRestClient.this.initializers.forEach(initializer -> initializer.initialize(request));
			}
			return request;
		}

		private static ResourceAccessException createResourceAccessException(URI url, HttpMethod method, IOException ex) {
			StringBuilder msg = new StringBuilder("I/O error on ");
			msg.append(method.name());
			msg.append(" request for \"");
			String urlString = url.toString();
			int idx = urlString.indexOf('?');
			if (idx != -1) {
				msg.append(urlString, 0, idx);
			}
			else {
				msg.append(urlString);
			}
			msg.append("\": ");
			msg.append(ex.getMessage());
			return new ResourceAccessException(msg.toString(), ex);
		}


		@FunctionalInterface
		private interface InternalBody {

			void writeTo(ClientHttpRequest request) throws IOException;
		}
	}

	private class DefaultResponseSpec implements ResponseSpec {

		private final HttpRequest clientRequest;

		private final ClientHttpResponse clientResponse;

		private final List<StatusHandler> statusHandlers = new ArrayList<>(1);

		private final int defaultStatusHandlerCount;


		DefaultResponseSpec(HttpRequest clientRequest, ClientHttpResponse clientResponse) {
			this.clientRequest = clientRequest;
			this.clientResponse = clientResponse;
			this.statusHandlers.addAll(DefaultRestClient.this.defaultStatusHandlers);
			this.statusHandlers.add(StatusHandler.defaultHandler(DefaultRestClient.this.messageConverters));
			this.defaultStatusHandlerCount = this.statusHandlers.size();
		}

		@Override
		public ResponseSpec onStatus(Predicate<HttpStatusCode> statusPredicate, ErrorHandler errorHandler) {
			Assert.notNull(statusPredicate, "StatusPredicate must not be null");
			Assert.notNull(errorHandler, "ErrorHandler must not be null");

			return onStatusInternal(StatusHandler.of(statusPredicate, errorHandler));
		}

		@Override
		public ResponseSpec onStatus(ResponseErrorHandler errorHandler) {
			Assert.notNull(errorHandler, "ResponseErrorHandler must not be null");

			return onStatusInternal(StatusHandler.fromErrorHandler(errorHandler));
		}

		private ResponseSpec onStatusInternal(StatusHandler statusHandler) {
			Assert.notNull(statusHandler, "StatusHandler must not be null");

			int index = this.statusHandlers.size() - this.defaultStatusHandlerCount;  // Default handlers always last
			this.statusHandlers.add(index, statusHandler);
			return this;
		}

		@Override
		public <T> T body(Class<T> bodyType) {
			return readWithMessageConverters(bodyType, bodyType);
		}

		@Override
		public <T> T body(ParameterizedTypeReference<T> bodyType) {
			Type type = bodyType.getType();
			Class<T> bodyClass = bodyClass(type);
			return readWithMessageConverters(type, bodyClass);
		}

		@Override
		public <T> ResponseEntity<T> toEntity(Class<T> bodyType) {
			return toEntityInternal(bodyType, bodyType);
		}

		@Override
		public <T> ResponseEntity<T> toEntity(ParameterizedTypeReference<T> bodyType) {
			Type type = bodyType.getType();
			Class<T> bodyClass = bodyClass(type);
			return toEntityInternal(type, bodyClass);
		}

		private <T> ResponseEntity<T> toEntityInternal(Type bodyType, Class<T> bodyClass) {
			T body = readWithMessageConverters(bodyType, bodyClass);
			try {
				return ResponseEntity.status(this.clientResponse.getStatusCode())
						.headers(this.clientResponse.getHeaders())
						.body(body);
			}
			catch (IOException ex) {
				throw new ResourceAccessException("Could not retrieve response status code: " + ex.getMessage(), ex);
			}
		}

		@Override
		public ResponseEntity<Void> toBodilessEntity() {
			try (this.clientResponse) {
				applyStatusHandlers(this.clientRequest, this.clientResponse);
				return ResponseEntity.status(this.clientResponse.getStatusCode())
						.headers(this.clientResponse.getHeaders())
						.build();
			}
			catch (IOException ex) {
				throw new ResourceAccessException("Could not retrieve response status code: " + ex.getMessage(), ex);
			}
		}


		@SuppressWarnings("unchecked")
		private static <T> Class<T> bodyClass(Type type) {
			if (type instanceof Class<?> clazz) {
				return (Class<T>) clazz;
			}
			if (type instanceof ParameterizedType parameterizedType &&
					parameterizedType.getRawType() instanceof Class<?> rawType) {
				return (Class<T>) rawType;
			}
			return (Class<T>) Object.class;
		}


		@SuppressWarnings({"unchecked", "rawtypes"})
		private <T> T readWithMessageConverters(Type bodyType, Class<T> bodyClass) {
			MediaType contentType = getContentType();

			try (this.clientResponse) {
				applyStatusHandlers(this.clientRequest, this.clientResponse);

				for (HttpMessageConverter<?> messageConverter : DefaultRestClient.this.messageConverters) {
					if (messageConverter instanceof GenericHttpMessageConverter genericHttpMessageConverter) {
						if (genericHttpMessageConverter.canRead(bodyType, bodyClass, contentType)) {
							if (logger.isDebugEnabled()) {
								logger.debug("Reading to [" + ResolvableType.forType(bodyType) + "]");
							}
							return (T) genericHttpMessageConverter.read(bodyType, bodyClass, this.clientResponse);
						}
					}
					if (messageConverter.canRead(bodyClass, contentType)) {
						if (logger.isDebugEnabled()) {
							logger.debug("Reading to [" + bodyClass.getName() + "] as \"" + contentType + "\"");
						}
						return (T) messageConverter.read((Class)bodyClass, this.clientResponse);
					}
				}
				throw new UnknownContentTypeException(bodyType, contentType,
						this.clientResponse.getStatusCode(), this.clientResponse.getStatusText(),
						this.clientResponse.getHeaders(), RestClientUtils.getBody(this.clientResponse));
			}
			catch (IOException | HttpMessageNotReadableException ex) {
				throw new RestClientException("Error while extracting response for type [" +
						ResolvableType.forType(bodyType) + "] and content type [" + contentType + "]", ex);
			}
		}

		private MediaType getContentType() {
			MediaType contentType = this.clientResponse.getHeaders().getContentType();
			if (contentType == null) {
				contentType = MediaType.APPLICATION_OCTET_STREAM;
			}
			return contentType;
		}

		private void applyStatusHandlers(HttpRequest request, ClientHttpResponse response) throws IOException {
			for (StatusHandler handler : this.statusHandlers) {
				if (handler.test(response)) {
					handler.handle(request, response);
					return;
				}
			}
		}

	}

}
