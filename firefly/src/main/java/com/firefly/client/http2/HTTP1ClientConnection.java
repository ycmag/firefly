package com.firefly.client.http2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.firefly.codec.http2.decode.HttpParser;
import com.firefly.codec.http2.decode.HttpParser.RequestHandler;
import com.firefly.codec.http2.decode.HttpParser.ResponseHandler;
import com.firefly.codec.http2.encode.HttpGenerator;
import com.firefly.codec.http2.frame.SettingsFrame;
import com.firefly.codec.http2.model.HttpField;
import com.firefly.codec.http2.model.HttpHeader;
import com.firefly.codec.http2.model.HttpHeaderValue;
import com.firefly.codec.http2.model.HttpVersion;
import com.firefly.codec.http2.stream.AbstractHTTP1Connection;
import com.firefly.codec.http2.stream.AbstractHTTP1OutputStream;
import com.firefly.codec.http2.stream.HTTP2Configuration;
import com.firefly.codec.http2.stream.HTTPConnection;
import com.firefly.codec.http2.stream.Session.Listener;
import com.firefly.codec.http2.stream.Stream;
import com.firefly.net.Session;
import com.firefly.net.tcp.ssl.SSLSession;
import com.firefly.utils.codec.Base64Utils;
import com.firefly.utils.concurrent.Promise;
import com.firefly.utils.io.BufferUtils;
import com.firefly.utils.log.Log;
import com.firefly.utils.log.LogFactory;

public class HTTP1ClientConnection extends AbstractHTTP1Connection {

	private static final Log log = LogFactory.getInstance().getLog("firefly-system");

	private final ResponseHandlerWrap wrap;
	volatile boolean upgradeHTTP2Successfully = false;

	private static class ResponseHandlerWrap implements ResponseHandler {

		private final AtomicReference<HTTP1ClientResponseHandler> writing = new AtomicReference<>();
		private int status;
		private String reason;
		private HTTP1ClientConnection connection;

		@Override
		public void earlyEOF() {
			writing.get().earlyEOF();
		}

		@Override
		public boolean content(ByteBuffer item) {
			return writing.get().content(item);
		}

		@Override
		public boolean headerComplete() {
			return writing.get().headerComplete();
		}

		@Override
		public boolean messageComplete() {
			if (status == 100 && "Continue".equalsIgnoreCase(reason)) {
				log.debug("client received the 100 Continue response");
				connection.getParser().reset();
				return true;
			} else {
				return writing.getAndSet(null).messageComplete();
			}
		}

		@Override
		public void parsedHeader(HttpField field) {
			writing.get().parsedHeader(field);
		}

		@Override
		public void badMessage(int status, String reason) {
			writing.get().badMessage(status, reason);
		}

		@Override
		public int getHeaderCacheSize() {
			return 1024;
		}

		@Override
		public boolean startResponse(HttpVersion version, int status, String reason) {
			this.status = status;
			this.reason = reason;
			return writing.get().startResponse(version, status, reason);
		}

	}

	public HTTP1ClientConnection(HTTP2Configuration config, Session tcpSession, SSLSession sslSession) {
		this(config, sslSession, tcpSession, new ResponseHandlerWrap());
	}

	private HTTP1ClientConnection(HTTP2Configuration config, SSLSession sslSession, Session tcpSession,
			ResponseHandler responseHandler) {
		super(config, sslSession, tcpSession, null, responseHandler);
		wrap = (ResponseHandlerWrap) responseHandler;
		wrap.connection = this;
	}

	@Override
	protected HttpParser initHttpParser(HTTP2Configuration config, RequestHandler requestHandler,
			ResponseHandler responseHandler) {
		return new HttpParser(responseHandler, config.getMaxRequestHeadLength());
	}

	@Override
	protected HttpGenerator initHttpGenerator() {
		return new HttpGenerator();
	}

	HttpParser getParser() {
		return parser;
	}

	HttpGenerator getGenerator() {
		return generator;
	}

	SSLSession getSSLSession() {
		return sslSession;
	}

	HTTP2Configuration getHTTP2Configuration() {
		return config;
	}

	Session getTcpSession() {
		return tcpSession;
	}

	public void upgradeHTTP2WithCleartext(HTTPClientRequest request, SettingsFrame settings,
			final Promise<HTTPConnection> promise, final Promise<Stream> initStream, final Stream.Listener initStreamListener, final Listener listener, final HTTP1ClientResponseHandler handler) {
		if (isEncrypted()) {
			throw new IllegalStateException("The TLS TCP connection must use ALPN to upgrade HTTP2");
		}

		handler.promise = promise;
		handler.listener = listener;
		handler.initStream = initStream;
		handler.initStreamListener = initStreamListener;

		// generate http2 upgrading headers
		request.getFields().add(new HttpField(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"));
		request.getFields().add(new HttpField(HttpHeader.UPGRADE, "h2c"));
		if (settings != null) {
			List<ByteBuffer> byteBuffers = http2Generator.control(settings);
			if (byteBuffers != null && byteBuffers.size() > 0) {
				try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
					for (ByteBuffer buffer : byteBuffers) {
						out.write(BufferUtils.toArray(buffer));
					}
					byte[] settingsFrame = out.toByteArray();
					byte[] settingsPayload = new byte[settingsFrame.length - 9];
					System.arraycopy(settingsFrame, 9, settingsPayload, 0, settingsPayload.length);

					request.getFields().add(new HttpField(HttpHeader.HTTP2_SETTINGS,
							Base64Utils.encodeToUrlSafeString(settingsPayload)));
				} catch (IOException e) {
					log.error("generate http2 upgrading settings exception", e);
				}
			} else {
				request.getFields().add(new HttpField(HttpHeader.HTTP2_SETTINGS, ""));
			}
		} else {
			request.getFields().add(new HttpField(HttpHeader.HTTP2_SETTINGS, ""));
		}

		request(request, handler);
	}

	public HTTP1ClientRequestOutputStream requestWith100Continue(HTTPClientRequest request,
			HTTP1ClientResponseHandler handler) {
		request.getFields().put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE);
		handler.continueOutput = requestWithStream(request, handler);
		try {
			handler.continueOutput.commit();
		} catch (IOException e) {
			generator.reset();
			log.error("client generates the HTTP message exception", e);
		}
		return handler.continueOutput;
	}

	public void request(HTTPClientRequest request, HTTP1ClientResponseHandler handler) {
		try (HTTP1ClientRequestOutputStream output = requestWithStream(request, handler)) {
			log.debug("client request and does not send data");
		} catch (IOException e) {
			generator.reset();
			log.error("client generates the HTTP message exception", e);
		}
	}

	public void request(HTTPClientRequest request, ByteBuffer data, HTTP1ClientResponseHandler handler) {
		try (HTTP1ClientRequestOutputStream output = requestWithStream(request, handler)) {
			if (data != null) {
				output.writeWithContentLength(data);
			}
		} catch (IOException e) {
			generator.reset();
			log.error("client generates the HTTP message exception", e);
		}
	}

	public void request(HTTPClientRequest request, ByteBuffer[] dataArray, HTTP1ClientResponseHandler handler) {
		try (HTTP1ClientRequestOutputStream output = requestWithStream(request, handler)) {
			if (dataArray != null) {
				output.writeWithContentLength(dataArray);
			}
		} catch (IOException e) {
			generator.reset();
			log.error("client generates the HTTP message exception", e);
		}
	}

	public HTTP1ClientRequestOutputStream requestWithStream(HTTPClientRequest request,
			HTTP1ClientResponseHandler handler) {
		checkWrite(request, handler);
		request.getFields().put(HttpHeader.HOST, tcpSession.getRemoteAddress().getHostString());
		HTTP1ClientRequestOutputStream outputStream = new HTTP1ClientRequestOutputStream(this, request);
		return outputStream;
	}

	public static class HTTP1ClientRequestOutputStream extends AbstractHTTP1OutputStream {

		private final HTTP1ClientConnection connection;

		private HTTP1ClientRequestOutputStream(HTTP1ClientConnection connection, HTTPClientRequest request) {
			super(request, true);
			this.connection = connection;
		}

		@Override
		protected void generateHTTPMessageSuccessfully() {
			log.debug("client session {} generates the HTTP message completely", connection.tcpSession.getSessionId());
			connection.generator.reset();
		}

		@Override
		protected void generateHTTPMessageExceptionally(HttpGenerator.Result generatorResult,
				HttpGenerator.State generatorState) {
			if (log.isDebugEnabled()) {
				log.debug("http1 generator error, the result is {}, and the generator state is {}", generatorResult,
						generatorState);
			}
			connection.getGenerator().reset();
			throw new IllegalStateException("client generates http message exception.");
		}

		@Override
		protected ByteBuffer getHeaderByteBuffer() {
			return BufferUtils.allocate(connection.getHTTP2Configuration().getMaxRequestHeadLength());
		}

		@Override
		protected Session getSession() {
			return connection.getTcpSession();
		}

		@Override
		protected HttpGenerator getHttpGenerator() {
			return connection.getGenerator();
		}
	}

	private void checkWrite(HTTPClientRequest request, HTTP1ClientResponseHandler handler) {
		if (request == null)
			throw new IllegalArgumentException("the http client request is null");

		if (handler == null)
			throw new IllegalArgumentException("the http1 client response handler is null");

		if (!isOpen())
			throw new IllegalStateException("current client session " + tcpSession.getSessionId() + " has been closed");

		if (upgradeHTTP2Successfully)
			throw new IllegalStateException(
					"current client session " + tcpSession.getSessionId() + " has upgraded HTTP2");

		if (wrap.writing.compareAndSet(null, handler)) {
			handler.connection = this;
			handler.request = request;
		} else {
			throw new WritePendingException();
		}
	}

}
