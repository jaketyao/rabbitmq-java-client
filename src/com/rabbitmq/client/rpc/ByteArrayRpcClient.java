// The contents of this file are subject to the Mozilla Public License
// Version 1.1 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License
// at http://www.mozilla.org/MPL/
//
// Software distributed under the License is distributed on an "AS IS"
// basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
// the License for the specific language governing rights and
// limitations under the License.
//
// The Original Code is RabbitMQ.
//
// The Initial Developer of the Original Code is VMware, Inc.
// Copyright (c) 2007-2011 VMware, Inc.  All rights reserved.
//

package com.rabbitmq.client.rpc;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeoutException;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;

import com.rabbitmq.utility.BlockingCell;

/**
 * A class that sends Remote Procedure Call (Rpc) requests on a (fixed) {@link Channel} and waits
 * for the replies. Can send requests to multiple servers concurrently. The server (to which the
 * request is sent) is identified by an exchange and routing-key, supplied on each request. Although
 * each thread that sends a request blocks until the reply is received, multiple threads can send a
 * request without holding each other up. Responses are subject to a (specified) timeout period,
 * after which a {@link TimeoutException} is thrown.
 * <p/>
 * This class manages a single queue used as a reply-to queue on all the requests. The queue
 * persists until the client is closed. After it is closed it cannot be re-opened.
 * <p/>
 * In this basic class requests (and responses) are byte arrays.
 * <p/>
 * <b>Concurrent Semantics</b><br/>
 * This class is thread-safe. Multiple calls may be issued on multiple threads without blocking each
 * other.
 */
public class ByteArrayRpcClient implements RpcClient<byte[], byte[]> {
    /** NO_TIMEOUT value must match convention on {@link BlockingCell#uninterruptibleGet(int)} */
    public final static int NO_TIMEOUT = -1;

    /** Channel we communicate on */
    private final Channel channel;
    /** timeout milliseconds to wait for responses */
    private final int timeout;

    /** Whether the caller is setup for calls */
    private volatile boolean isOpen = false;
    /** Whether the caller has ever been closed */
    private volatile boolean hasBeenClosed = false;
    /** Monitor to protect non-final values in state */
    private final Object monitor = new Object();
    /** The name of our private reply queue */
    private String replyQueue = null;
    /** Consumer and its tag attached to our reply queue */
    private Consumer consumer;
    private String consumerTag;

    /** Map from request correlation ID to continuation BlockingCell */
    private final Map<String, BlockingCell<RpcReturn>> continuationMap = new HashMap<String, BlockingCell<RpcReturn>>();
    /** Contains the most recently-used request correlation ID */
    private int correlationId;

    /**
     * Construct a new {@link RpcClient} that will send requests on the given channel, waiting for
     * responses.
     * @param channel the channel to use for communication
     * @param timeout time (ms) to allow for each response
     */
    public ByteArrayRpcClient(Channel channel, int timeout) {
        this.channel = channel;
        if (timeout < NO_TIMEOUT)
            throw new IllegalArgumentException(
                    "Timeout argument must be NO_TIMEOUT(-1) or non-negative.");
        this.timeout = timeout;
        this.correlationId = 0;
        this.isOpen = false;
    }

    /**
     * Construct a {@link RpcClient} that will communicate on the given channel, sending requests on
     * it and waiting for responses.
     * <p/>
     * Waits forever for responses (that is, no timeout).
     * @param channel the channel to use for communication
     */
    public ByteArrayRpcClient(Channel channel) {
        this(channel, NO_TIMEOUT);
    }

    /**
     * Private API - ensures the RpcClient is currently open.
     * @throws IOException if an error is encountered
     */
    private void checkIsOpen() throws IOException {
        if (!this.isOpen) {
            throw new EOFException("RPC Client is not open");
        }
    }

    /**
     * Private API - ensures the RpcClient has not been closed.
     * @throws IOException if the caller has been closed
     */
    private void checkWasNotClosed() throws IOException {
        if (this.hasBeenClosed) {
            throw new EOFException("RPC Client has been closed");
        }
    }

    public void close() throws IOException {
        synchronized (this.monitor) {
            if (!this.hasBeenClosed && this.isOpen) {
                this.channel.basicCancel(this.consumerTag);
                this.isOpen = false;
                this.hasBeenClosed = true;
            }
        }
    }

    private static String setupReplyQueue(Channel channel) throws IOException {
        return channel.queueDeclare("", false, true, true, null).getQueue();
    }

    /**
     * Returns an RPC consumer.
     * @return the newly created consumer
     */
    private static Consumer createConsumer(Channel channel,
            final Map<String, BlockingCell<RpcReturn>> continuationMap) {
        return new DefaultConsumer(channel) {
            @Override
            public void handleShutdownSignal(String consumerTag,
                    ShutdownSignalException signal) {
                synchronized (continuationMap) {
                    for (Entry<String, BlockingCell<RpcReturn>> entry : continuationMap
                            .entrySet()) {
                        entry.getValue().set(new RpcReturn(signal));
                    }
                }
            }

            @Override
            public void handleDelivery(String consumerTag, Envelope envelope,
                    AMQP.BasicProperties properties, byte[] body)
                    throws IOException {
                synchronized (continuationMap) {
                    String replyId = properties.getCorrelationId();
                    BlockingCell<RpcReturn> blocker = continuationMap
                            .get(replyId);
                    if (blocker == null) return; // ignore if we don't recognise it.

                    continuationMap.remove(replyId);
                    blocker.set(ByteArrayRpcClient.createRpcReturn(body,
                            properties.getHeaders()));
                }
            }
        };
    }

    static RpcReturn createRpcReturn(byte[] body, Map<String, Object> hdrs) {
        if (hdrs == null) return new RpcReturn(body);
        if (hdrs.containsKey(RpcException.RPC_EXCEPTION_HEADER))
            return new RpcReturn(hdrs.get(RpcException.RPC_EXCEPTION_HEADER)
                    .toString(), body);
        return new RpcReturn(body);
    }

    /**
     * A simple class wrapping up the result of a remote procedure call.
     */
    private static class RpcReturn {
        private final ShutdownSignalException signal;
        private final byte[] result;
        private final String exceptionHeader;

        private RpcReturn(byte[] result, ShutdownSignalException signal,
                String exceptionHeader) {
            this.result = result;
            this.signal = signal;
            this.exceptionHeader = exceptionHeader;
        }

        public RpcReturn(byte[] result) {
            this(result, null, null);
        }

        public RpcReturn(ShutdownSignalException signal) {
            this(null, signal, null);
        }

        public RpcReturn(String exceptionHeader, byte[] exceptionMsg) {
            this(exceptionMsg, null, exceptionHeader);
        }

        public ShutdownSignalException getSignal() {
            return this.signal;
        }

        public byte[] getResult() {
            return this.result;
        }

        public String getExceptionHeader() {
            return this.exceptionHeader;
        }

        public boolean shutdown() {
            return this.signal != null;
        }

        public boolean exception() {
            return this.exceptionHeader != null;
        }
    }

    private void publish(String exchange, String routingKey,
            AMQP.BasicProperties props, byte[] message) throws IOException {
        this.channel.basicPublish(exchange, routingKey, props, message);
    }

    private byte[] primitiveCall(String exchange, String routingKey,
            AMQP.BasicProperties props, byte[] message) throws IOException,
            ShutdownSignalException, TimeoutException {
        BlockingCell<RpcReturn> k;
        synchronized (this.monitor) {
            this.checkIsOpen();
            k = new BlockingCell<RpcReturn>();
            synchronized (this.continuationMap) {
                this.correlationId++;
                String replyId = "" + this.correlationId;
                props = ((props == null) ? new AMQP.BasicProperties.Builder()
                        : props.builder()).correlationId(replyId)
                        .replyTo(this.replyQueue).build();
                this.continuationMap.put(replyId, k);
            }
            this.publish(exchange, routingKey, props, message);
        }
        RpcReturn reply = k.uninterruptibleGet(this.timeout);
        if (reply.shutdown()) {
            throw wrapSSE(reply.getSignal());
        } else if (reply.exception()) {
            throw newRuntimeException(reply.getExceptionHeader(),
                    reply.getResult());
        } else {
            return reply.getResult();
        }
    }

    private static RuntimeException newRuntimeException(String exceptionHeader,
            byte[] message) {
        RuntimeException rte = null;
        if (exceptionHeader == RpcException.RPC_EXCEPTION_HEADER_RPC_EXCEPTION) {
            rte = RpcException.newRpcException(new String(message));
        } else if (exceptionHeader == RpcException.RPC_EXCEPTION_HEADER_SERVICE_EXCEPTION) {
            rte = ServiceException.newServiceException(new String(message));
        } else {
            rte = new RuntimeException(
                    "Rpc call returned unexpected exception: "
                            + exceptionHeader + ":" + new String(message));
        }
        return rte;
    }

    private static ShutdownSignalException wrapSSE(ShutdownSignalException sig) {
        return (ShutdownSignalException) new ShutdownSignalException(
                sig.isHardError(), sig.isInitiatedByApplication(),
                sig.getReason(), sig.getReference()).initCause(sig);
    }

    public byte[] call(String exchange, String routingKey, byte[] message)
            throws IOException, ShutdownSignalException, TimeoutException {
        return primitiveCall(exchange, routingKey, null, message);
    }

    /**
     * Retrieve the reply queue.
     * @return the name of the client's reply queue, or null if it has never been set
     */
    public String getReplyQueue() {
        synchronized (this.monitor) {
            return this.replyQueue;
        }
    }

    public void open() throws IOException {
        synchronized (this.monitor) {
            this.checkWasNotClosed();
            this.replyQueue = setupReplyQueue(channel);
            this.consumer = createConsumer(channel, this.continuationMap);
            this.consumerTag = channel.basicConsume(this.replyQueue, true,
                    this.consumer);
            this.isOpen = true;
        }
    }
}