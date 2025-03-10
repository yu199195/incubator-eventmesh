/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.client.tcp.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.eventmesh.client.tcp.SimpleSubClient;
import org.apache.eventmesh.client.tcp.common.EventMeshCommon;
import org.apache.eventmesh.client.tcp.common.MessageUtils;
import org.apache.eventmesh.client.tcp.common.ReceiveMsgHook;
import org.apache.eventmesh.client.tcp.common.RequestContext;
import org.apache.eventmesh.client.tcp.common.TcpClient;
import org.apache.eventmesh.common.protocol.SubcriptionType;
import org.apache.eventmesh.common.protocol.SubscriptionItem;
import org.apache.eventmesh.common.protocol.SubscriptionMode;
import org.apache.eventmesh.common.protocol.tcp.*;

import org.apache.eventmesh.common.protocol.tcp.Package;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleSubClientImpl extends TcpClient implements SimpleSubClient {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    private UserAgent userAgent;

    private ReceiveMsgHook callback;

    private List<SubscriptionItem> subscriptionItems = new ArrayList<SubscriptionItem>();

    private ScheduledFuture<?> task;

    public SimpleSubClientImpl(String accessIp, int port, UserAgent agent) {
        super(accessIp, port);
        this.userAgent = agent;
    }

    public void registerBusiHandler(ReceiveMsgHook handler) throws Exception {
        callback = handler;
    }

    public void init() throws Exception {
        open(new Handler());
        hello();
        logger.info("SimpleSubClientImpl|{}|started!", clientNo);
    }

    public void reconnect() throws Exception {
        super.reconnect();
        hello();
        if (!CollectionUtils.isEmpty(subscriptionItems)) {
            for (SubscriptionItem item : subscriptionItems) {
                Package request = MessageUtils.subscribe(item.getTopic(), item.getMode(), item.getType());
                this.io(request, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
            }
        }
        listen();
    }

    public void close() {
        try {
            task.cancel(false);
            goodbye();
            super.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void heartbeat() throws Exception {
        task = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isActive()) {
                        SimpleSubClientImpl.this.reconnect();
                    }
                    Package msg = MessageUtils.heartBeat();
                    SimpleSubClientImpl.this.io(msg, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
                } catch (Exception e) {
                }
            }
        }, EventMeshCommon.HEATBEAT, EventMeshCommon.HEATBEAT, TimeUnit.MILLISECONDS);
    }

    private void goodbye() throws Exception {
        Package msg = MessageUtils.goodbye();
        this.io(msg, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
    }

    private void hello() throws Exception {
        Package msg = MessageUtils.hello(userAgent);
        this.io(msg, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
    }

    public void listen() throws Exception {
        Package request = MessageUtils.listen();
        this.io(request, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
    }


    public void subscribe(String topic, SubscriptionMode subscriptionMode, SubcriptionType subcriptionType) throws Exception {
        subscriptionItems.add(new SubscriptionItem(topic, subscriptionMode, subcriptionType));
        Package request = MessageUtils.subscribe(topic, subscriptionMode, subcriptionType);
        this.io(request, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
    }

    public void unsubscribe() throws Exception {
        Package request = MessageUtils.unsubscribe();
        this.io(request, EventMeshCommon.DEFAULT_TIME_OUT_MILLS);
    }

    public UserAgent getUserAgent() {
        return userAgent;
    }

    @ChannelHandler.Sharable
    private class Handler extends SimpleChannelInboundHandler<Package> {
        @SuppressWarnings("Duplicates")
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Package msg) throws Exception {
            Command cmd = msg.getHeader().getCommand();
            logger.info(SimpleSubClientImpl.class.getSimpleName() + "|receive|type={}|msg={}", cmd, msg);
            if (cmd == Command.REQUEST_TO_CLIENT) {
                if (callback != null) {
                    callback.handle(msg, ctx);
                }
                Package pkg = MessageUtils.requestToClientAck(msg);
                send(pkg);
            } else if (cmd == Command.ASYNC_MESSAGE_TO_CLIENT) {
                Package pkg = MessageUtils.asyncMessageAck(msg);
                if (callback != null) {
                    callback.handle(msg, ctx);
                }
                send(pkg);
            } else if (cmd == Command.BROADCAST_MESSAGE_TO_CLIENT) {
                Package pkg = MessageUtils.broadcastMessageAck(msg);
                if (callback != null) {
                    callback.handle(msg, ctx);
                }
                send(pkg);
            } else if (cmd == Command.SERVER_GOODBYE_REQUEST) {
                //TODO
            } else {
                logger.error("msg ignored|{}|{}", cmd, msg);
            }
            RequestContext context = contexts.get(RequestContext._key(msg));
            if (context != null) {
                contexts.remove(context.getKey());
                context.finish(msg);
                return;
            } else {
                logger.error("msg ignored,context not found.|{}|{}", cmd, msg);
                return;
            }
        }
    }

    @Override
    public String toString() {
        return "SimpleSubClientImpl|clientNo=" + clientNo + "|" + userAgent;
    }
}
