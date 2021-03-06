//
// MessagePack-RPC for Java
//
// Copyright (C) 2010 Kazuki Ohta
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.msgpack.rpc.client.transport;

import static org.jboss.netty.channel.Channels.pipeline;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.msgpack.rpc.client.Address;
import org.msgpack.rpc.client.EventLoop;
import org.msgpack.rpc.client.RPCException;
import org.msgpack.rpc.client.netty.RPCRequestEncoder;
import org.msgpack.rpc.client.netty.RPCResponseDecoder;

/**
 * The netty ChannelHandler class. When some network events are occurred, the
 * methods of this class are called (e,g. connection establishment, message
 * receipt, etc.).
 */
class TCPClientHandler extends SimpleChannelHandler {
    protected final TCPSocket sock;
    
    public TCPClientHandler(TCPSocket sock) {
        super();
        this.sock = sock;
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent ev) {
        try {
            sock.onConnected();
        } catch (Exception e) {
            e.printStackTrace();
            sock.onConnectFailed();
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent ev) {
        try {
            sock.onMessageReceived(ev.getMessage());
        } catch (RPCException e) {
            // This is an internal error, don't propagate the exception to
            // the upper layers.
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
            sock.onFailed(e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent ev) {
        Throwable e = ev.getCause();
        if (e instanceof ConnectException) {
            sock.onConnectFailed();
        } else if (e instanceof RPCException) {
            // This is an internal error, don't propagate the exception to
            // the upper layers.
            e.printStackTrace();
        } else {
            sock.onFailed(new IOException(e.getMessage()));
        }
    }
}

/**
 * The netty PipelineFactory class. The methods of this class is called, when
 * some network events are occurred.
 */
class TCPClientPipelineFactory implements ChannelPipelineFactory {
    protected final TCPSocket sock;
    
    public TCPClientPipelineFactory(TCPSocket sock) {
        this.sock = sock;
    }

    public ChannelPipeline getPipeline() throws Exception {
        ChannelPipeline pipeline = pipeline();
        pipeline.addLast("encoder", new RPCRequestEncoder());        
        pipeline.addLast("decoder", new RPCResponseDecoder());
        pipeline.addLast("client", new TCPClientHandler(sock));
        return pipeline;
    }
}

/**
 * TCPSocket establishes the connection, and also sends/receives the object.
 */
public class TCPSocket {   
    protected final Address address;
    protected final EventLoop loop;
    protected final TCPTransport transport;
    
    // netty-specific members
    protected ClientBootstrap bootstrap;
    protected ChannelFuture connectFuture;
    protected Channel channel;

    public TCPSocket(Address address, EventLoop loop, TCPTransport transport) {
        this.address = address;
        this.loop = loop;
        this.transport = transport;
        this.connectFuture = null;
        this.channel = null;
        this.bootstrap = loop.createSocketBootstrap();
        this.bootstrap.setPipelineFactory(new TCPClientPipelineFactory(this));
    }
    
    /**
     * Try to connect to the server.
     * @throws Exception
     */
    protected synchronized void tryConnect() throws Exception {
        if (connectFuture != null)
            throw new IOException("already connected");
        connectFuture = bootstrap.connect(new InetSocketAddress(address.getHost(), address.getPort()));
    }
    
    /**
     * Try to send the message.
     * @param msg the message to send
     * @throws Exception
     */
    public synchronized void trySend(Object msg) throws Exception {
        if (connectFuture == null || channel == null)
            throw new IOException("not connected, but try send");
        channel.write(msg);
    }

    /**
     * Try to close the connection.
     */
    public synchronized void tryClose() {
        if (channel != null && channel.isOpen())
            channel.close().awaitUninterruptibly();
        connectFuture = null;
        channel = null;
    }

    /**
     * The callback function, called when the connection is established.
     * @throws Exception
     */
    public void onConnected() throws Exception {
        boolean isSuccess = false;
        synchronized(this) {
            // connected, but onConnected() called
            if (channel != null)
                throw new IOException("already connected");
            // onConnected() called without tryConnect
            if (connectFuture == null)
                throw new IOException("tryConnect was not called");

            // set channel
            channel = connectFuture.awaitUninterruptibly().getChannel();
            isSuccess = connectFuture.isSuccess();
        }
        if (isSuccess)
            transport.onConnected();
        else
            onConnectFailed();
    }

    /**
     * The callback function, called when the connection failed.
     */
    public void onConnectFailed() {
        transport.onConnectFailed();
        tryClose();
    }

    /**
     * The callback called when the message arrives
     * @param replyObject the received object, already unpacked.
     * @throws Exception
     */
    public void onMessageReceived(Object replyObject) throws Exception {
        transport.onMessageReceived(replyObject);
    }
    
    /**
     * The callback called when the connection closed.
     */
    public void onClosed() {
        transport.onClosed();
        tryClose();
    }
    
    /**
     * The callback called when the error occurred.
     * @param e occurred exception.
     */
    public void onFailed(Exception e) {
        transport.onFailed(e);
        tryClose();
    }
}
