package com.rex.websocket;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.ByteBuffer;

/**
 * WebSocket client
 * TODO: Support TLS
 */
public class WsClient {

    private static final Logger sLogger = LoggerFactory.getLogger(WsClient.class);

    private EventLoopGroup mGroup;
    private ChannelFuture mChannelFuture;
    private String mSubProtocol;

    public interface Callback {
        void onConnected(WsClient client);
        void onClosed(WsClient client);
        void onReceived(WsClient client, ByteBuffer data);
    }
    private Callback mCallback;

    public WsClient() {
        sLogger.trace("<init>");
    }

    synchronized public WsClient start(final URI uri) {
        sLogger.trace("start uri:<{}>", uri);
        if (mChannelFuture != null) {
            sLogger.warn("already started");
            return this;
        }

        String scheme = uri.getScheme() == null? "ws" : uri.getScheme();
        final String host = uri.getHost() == null? "127.0.0.1" : uri.getHost();
        final int port;
        if (uri.getPort() == -1) {
            if ("ws".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("wss".equalsIgnoreCase(scheme)) {
                port = 443;
            } else {
                port = -1;
            }
        } else {
            port = uri.getPort();
        }
        if (port == -1) {
            sLogger.error("Unknown port");
            return this;
        }
        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            sLogger.error("Only WS(S) is supported.");
            return this;
        }
        sLogger.trace("scheme:{} host:{} port:{}", scheme, host, port);

        WebSocketClientProtocolHandler wsProtocolHandler = new WebSocketClientProtocolHandler(uri, WebSocketVersion.V13, mSubProtocol, false, null, 65535);
        mGroup = new NioEventLoopGroup();
        mChannelFuture = new Bootstrap()
                .group(mGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override // ChannelInitializer
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(1 << 16)) // 65536
                                .addLast(wsProtocolHandler)
                                .addLast(mWsHandler);
                    }
                })
                .connect(host, port).syncUninterruptibly();
        mChannelFuture.channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                sLogger.trace("closeFuture completed");
                if (mCallback != null) {
                    mCallback.onClosed(WsClient.this);
                }
            }
        });
        return this;
    }

    synchronized public WsClient stop() {
        sLogger.trace("stop");
        if (mChannelFuture == null) {
            sLogger.warn("not started");
            return this;
        }
        mChannelFuture.channel().close();
        mChannelFuture.channel()
                .closeFuture()
                .syncUninterruptibly();
        mChannelFuture = null;
        mGroup.shutdownGracefully();
        return this;
    }

    public WsClient setCallback(Callback cb) {
        mCallback = cb;
        return this;
    }

    public WsClient setSubProtocol(String subProtocol) {
        mSubProtocol = subProtocol;
        return this;
    }

    public void send(ByteBuffer data) {
        if (mChannelFuture == null) {
            sLogger.warn("not started");
            return;
        }

        sLogger.trace("data:{}", data.remaining());
        ByteBuf buffer = Unpooled.copiedBuffer(data);
        WebSocketFrame frame = new BinaryWebSocketFrame(buffer);
        mChannelFuture.channel().writeAndFlush(frame);
    }

    private ChannelInboundHandlerAdapter mWsHandler = new SimpleChannelInboundHandler<WebSocketFrame>() {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame msg) throws Exception {
            sLogger.trace("msg:{}", msg);
            if (msg instanceof BinaryWebSocketFrame) {
                BinaryWebSocketFrame frame = (BinaryWebSocketFrame) msg;
                if (mCallback != null) {
                    mCallback.onReceived(WsClient.this, frame.content().nioBuffer());
                }
            }
        }
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            super.userEventTriggered(ctx, evt);
            sLogger.trace("userEventTriggered evt:{}", evt);
            if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                if (mCallback != null) {
                    mCallback.onConnected(WsClient.this);
                }
            }
        }
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            sLogger.warn("failed\n", cause);
            if (mCallback != null) {
                mCallback.onClosed(WsClient.this);
            }
        }
    };
}
