package com.hedera.hcs.sxc.plugin.mirror.subscribe;



/*-
 * ‌
 * hcs-sxc-java
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hashgraph.proto.Timestamp;
import com.hedera.hashgraph.proto.mirror.ConsensusTopicResponse;
import com.hedera.hashgraph.sdk.mirror.MirrorClient;
import com.hedera.hashgraph.sdk.mirror.MirrorConsensusTopicQuery;
import com.hedera.hashgraph.sdk.mirror.MirrorConsensusTopicResponse;
import com.hedera.hcs.sxc.commonobjects.SxcConsensusMessage;
import com.hedera.hcs.sxc.interfaces.HCSCallBackFromMirror;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicId;
import com.hedera.hcs.sxc.proto.ApplicationMessageChunk;

/**
 * 
 * Class to manage Mirror node topic subscribers
 * the subscription is a blocking gRPC process which requires its own thread
 * if multiple topics are to be subscribed to
 */
@Log4j2
@Getter
@EqualsAndHashCode(callSuper=false)
public final class MirrorTopicSubscriber extends Thread {
    
    private String mirrorAddress = "";
    private int mirrorPort = 0;
    private ConsensusTopicId topicId;
    private Optional<Instant> subscribeFrom;
    private HCSCallBackFromMirror onHCSMessageCallback;
    private boolean testMode = false;
    
    public MirrorTopicSubscriber(String mirrorAddress, int mirrorPort, ConsensusTopicId topicId, Optional<Instant> subscribeFrom, HCSCallBackFromMirror onHCSMessageCallback, boolean testMode) {
        this.mirrorAddress = mirrorAddress;
        this.mirrorPort = mirrorPort;
        this.topicId = topicId;
        this.subscribeFrom = subscribeFrom;
        this.onHCSMessageCallback = onHCSMessageCallback;
        this.testMode = testMode;
    }
    
    public void run() {
        subscribe();
    }
    
    void subscribeForTest() {
        subscribe();
    }
    
    private void subscribe() {
        final MirrorClient mirrorClient = new MirrorClient(this.mirrorAddress+ ":" + this.mirrorPort);
        try {
            MirrorConsensusTopicQuery mirrorConsensusTopicQuery = new MirrorConsensusTopicQuery()
                    .setTopicId(topicId);
    
            log.debug("App Subscribing to topic number " + this.topicId.toString() + " on mirror node: " + this.mirrorAddress + ":" + this.mirrorPort);
    
            if (this.subscribeFrom.isPresent()) {
                this.subscribeFrom = Optional.of(this.subscribeFrom.get().plusNanos(1));
            } else {
                this.subscribeFrom = Optional.of(Instant.now());
            }
            
            log.debug("subscribing from " + this.subscribeFrom.get().getEpochSecond() + " seconds, " + this.subscribeFrom.get().getNano() + " nanos.");
    
            mirrorConsensusTopicQuery.setStartTime(this.subscribeFrom.get());
            
            if ( ! this.testMode) {
                mirrorConsensusTopicQuery.subscribe(mirrorClient, resp -> {
                    log.debug("Got mirror message, calling handler");
                    this.subscribeFrom = Optional.of(resp.consensusTimestamp.plusNanos(1));
                    try {
                        onMirrorMessage(resp, this.onHCSMessageCallback, this.topicId);
                    } catch (InvalidProtocolBufferException e) {
                        log.error(e);
                    }   
                },(error) -> {
                    // On gRPC error, print the stack trace
                    log.error(error);
                    log.debug("Sleeping 11s before attempting connection again");
                    Uninterruptibles.sleepUninterruptibly(Duration.ofSeconds(11));
                    log.debug("Attempting to reconnect");
                    subscribe();
                }
                );
            }        
        } catch (Exception e1) {
            log.error(e1);
            log.debug("Sleeping 11s before attempting connection again");
            Uninterruptibles.sleepUninterruptibly(Duration.ofSeconds(11));
        }
    }

    void onMirrorMessage(MirrorConsensusTopicResponse resp, HCSCallBackFromMirror onHCSMessageCallback, ConsensusTopicId topicId) throws InvalidProtocolBufferException {
        log.debug("Got message from mirror - persisting");
        ConsensusTopicResponse consensusTopicResponse = ConsensusTopicResponse.newBuilder()
                .setConsensusTimestamp(Timestamp.newBuilder().setSeconds(resp.consensusTimestamp.getEpochSecond())
                        .setNanos(resp.consensusTimestamp.getNano()).build())
                .setMessage(ByteString.copyFrom(resp.message)).setRunningHash(ByteString.copyFrom(resp.runningHash))
                .setSequenceNumber(resp.sequenceNumber).build();

        SxcConsensusMessage consensusMessage = new SxcConsensusMessage(topicId, consensusTopicResponse);
        onHCSMessageCallback.storeMirrorResponse(consensusMessage);

        byte[] message = resp.message;
        ApplicationMessageChunk messagePart;

        messagePart = ApplicationMessageChunk.parseFrom(message);
        log.debug("Got message from mirror - calling back");
        onHCSMessageCallback.partialMessage(messagePart, consensusMessage);

        log.debug("Got message from mirror - acknowledged");
    }
}
