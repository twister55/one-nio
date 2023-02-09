package one.nio.ws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.ws.exception.HandshakeException;
import one.nio.ws.exception.VersionException;
import one.nio.ws.exception.WebSocketException;
import one.nio.ws.extension.Extension;
import one.nio.ws.extension.ExtensionRequest;
import one.nio.ws.extension.ExtensionRequestParser;
import one.nio.ws.extension.PerMessageDeflate;
import one.nio.ws.message.BinaryMessage;
import one.nio.ws.message.CloseMessage;
import one.nio.ws.message.Message;
import one.nio.ws.message.MessageReader;
import one.nio.ws.message.MessageWriter;
import one.nio.ws.message.PingMessage;
import one.nio.ws.message.PongMessage;
import one.nio.ws.message.TextMessage;

/**
 * @author <a href="mailto:vadim.yelisseyev@gmail.com">Vadim Yelisseyev</a>
 */
public class WebSocketSession extends HttpSession {
    protected static final Log log = LogFactory.getLog(WebSocketSession.class);

    private final WebSocketServer server;
    private final WebSocketServerConfig config;
    private final List<Extension> extensions;

    private MessageReader reader;
    private MessageWriter writer;

    public WebSocketSession(Socket socket, WebSocketServer server, WebSocketServerConfig config) {
        super(socket, server);
        this.server = server;
        this.config = config;
        this.extensions = new ArrayList<>();
    }

    @Override
    public int checkStatus(long currentTime, long keepAlive) {
        if (currentTime - lastAccessTime < keepAlive) {
            return ACTIVE;
        }

        try {
            if (wasSelected) {
                sendMessage(PingMessage.EMPTY);
            }

            return ACTIVE;
        } catch (IOException e) {
            return STALE;
        }
    }

    @Override
    protected void processRead(byte[] buffer) throws IOException {
        if (reader == null) {
            super.processRead(buffer);
        } else {
            final Message<?> message = reader.read();
            if (message != null) {
                handleMessage(this, message);
            }
        }
    }

    public void handshake(Request request) throws IOException {
        final String version = request.getHeader(WebSocketHeaders.VERSION);
        if (!"13".equals(version)) {
            throw new VersionException(version);
        }
        if (request.getMethod() != Request.METHOD_GET) {
            throw new HandshakeException("only GET method supported");
        }
        if (request.getHeader(WebSocketHeaders.KEY) == null) {
            throw new HandshakeException("missing websocket key");
        }
        if (!WebSocketHeaders.isUpgradableRequest(request)) {
            throw new HandshakeException("missing upgrade header");
        }
        sendHandshakeResponse(request);
    }

    public void sendMessage(Message<?> message) throws IOException {
        if (writer == null) {
            throw new IllegalStateException("websocket message was sent before handshake");
        }
        writer.write(message);
    }

    protected void handleMessage(WebSocketSession session, Message<?> message) throws IOException {
        if (message instanceof PingMessage) {
            server.handleMessage(session, (PingMessage) message);
        } else if (message instanceof PongMessage) {
            server.handleMessage(session, (PongMessage) message);
        } else if (message instanceof TextMessage) {
            server.handleMessage(session, (TextMessage) message);
        } else if (message instanceof BinaryMessage) {
            server.handleMessage(session, (BinaryMessage) message);
        } else if (message instanceof CloseMessage) {
            server.handleMessage(session, (CloseMessage) message);
        }
    }

    @Override
    public void handleException(Throwable e) {
        log.error(e);
        if (e instanceof WebSocketException) {
            close(((WebSocketException) e).code());
            return;
        }
        super.handleException(e);
    }

    public void close(short code) {
        try {
            sendMessage(new CloseMessage(code));
        } catch (Exception e) {
            log.warn("error while sending closing frame", e);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        try {
            extensions.forEach(Extension::close);
        } finally {
            super.close();
        }
    }

    protected void sendHandshakeResponse(Request request) throws IOException {
        try {
            final Response response = createResponse(request);
            processExtensions(request, response);
            processProtocol(request, response);
            reader = new MessageReader(this, extensions, config.maxFramePayloadLength, config.maxMessagePayloadLength);
            writer = new MessageWriter(this, extensions);
            sendResponse(response);
        } catch (HandshakeException e) {
            sendError(Response.BAD_REQUEST, e.getMessage());
        }
    }

    protected Response createResponse(Request request) {
        try {
            Response response = new Response(Response.SWITCHING_PROTOCOLS, Response.EMPTY);
            response.addHeader("Upgrade: websocket");
            response.addHeader("Connection: Upgrade");
            response.addHeader(WebSocketHeaders.createAcceptHeader(request));
            return response;
        } catch (VersionException e) {
            Response response = new Response("426 Upgrade Required", Response.EMPTY);
            response.addHeader(WebSocketHeaders.createVersionHeader(13));
            return response;
        }
    }

    protected void processProtocol(Request request, Response response) {
        final String protocols = request.getHeader(WebSocketHeaders.PROTOCOL);
        if (protocols != null) {
            for (String protocol : protocols.split(",")) {
                if (config.isSupportedProtocol(protocol)) {
                    response.addHeader(WebSocketHeaders.PROTOCOL + protocol);
                    break;
                }
            }
        }
    }

    protected void processExtensions(Request request, Response response) {
        final StringBuilder responseHeaderBuilder = new StringBuilder(WebSocketHeaders.EXTENSIONS);
        final List<ExtensionRequest> extensionRequests = ExtensionRequestParser.parse(
                request.getHeader(WebSocketHeaders.EXTENSIONS)
        );
        for (ExtensionRequest extensionRequest : extensionRequests) {
            Extension extension = createExtension(extensionRequest);
            if (extension != null) {
                extensions.add(extension);
                if (extensions.size() > 1) {
                    responseHeaderBuilder.append(',');
                }
                extension.appendResponseHeaderValue(responseHeaderBuilder);
            }
        }
        if (!extensions.isEmpty()) {
            response.addHeader(responseHeaderBuilder.toString());
        }
    }

    protected Extension createExtension(ExtensionRequest request) {
        if (PerMessageDeflate.NAME.equals(request.getName())) {
            return PerMessageDeflate.negotiate(request.getParameters());
        }
        return null;
    }
}
