package com.hedera.hcs.sxc.callback;

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
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PublicKey;
import com.hedera.hcs.sxc.HCSCore;
import com.hedera.hcs.sxc.commonobjects.EncryptedData;
import com.hedera.hcs.sxc.commonobjects.HCSResponse;
import com.hedera.hcs.sxc.commonobjects.SxcConsensusMessage;
import com.hedera.hcs.sxc.config.Topic;
import com.hedera.hcs.sxc.consensus.OutboundHCSMessage;
import com.hedera.hcs.sxc.hashing.Hashing;
import com.hedera.hcs.sxc.interfaces.HCSCallBackFromMirror;
import com.hedera.hcs.sxc.interfaces.HCSCallBackToAppInterface;
import com.hedera.hcs.sxc.interfaces.SxcPersistence;
import com.hedera.hcs.sxc.interfaces.SxcKeyRotation;
import com.hedera.hcs.sxc.interfaces.SxcMessageEncryption;
import com.hedera.hcs.sxc.plugins.Plugins;
import com.hedera.hcs.sxc.utils.ByteUtil;

import lombok.extern.log4j.Log4j2;

import com.hedera.hcs.sxc.proto.ApplicationMessage;
import com.hedera.hcs.sxc.proto.ApplicationMessageChunk;
import com.hedera.hcs.sxc.proto.ApplicationMessageID;
import com.hedera.hcs.sxc.proto.KeyRotationInitialise;
import com.hedera.hcs.sxc.proto.KeyRotationRespond;
import com.hedera.hcs.sxc.utils.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import com.hedera.hcs.sxc.interfaces.SxcApplicationMessageInterface;
import com.hedera.hcs.sxc.proto.AccountID;
import com.hedera.hcs.sxc.proto.ConfirmProof;
import com.hedera.hcs.sxc.proto.RequestProof;
import com.hedera.hcs.sxc.proto.Timestamp;
import com.hedera.hcs.sxc.proto.VerifiableApplicationMessage;
import com.hedera.hcs.sxc.proto.VerifiableMessage;
import com.hedera.hcs.sxc.proto.VerifiedMessage;
import com.hedera.hcs.sxc.signing.Signing;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.lang3.SerializationUtils;

/**
 *
 * Implements callback registration and notification capabilities to support apps
 * See constructor for additional details {@link #OnHCSMessageCallback(com.hedera.hcs.sxc.HCSCore) }
 *
 */
@Log4j2
public final class OnHCSMessageCallback implements HCSCallBackFromMirror {

    private final List<HCSCallBackToAppInterface> observers = new ArrayList<>();
    private HCSCore hcsCore;
    private boolean signMessagesFromCore;
    private boolean encryptMessagesFromCore;
    private boolean rotateKeysFromCore;
    private Class<?> messageEncryptionClass;
    private SxcMessageEncryption messageEncryptionPlugin;
    private List<Topic> topics;
    private SxcKeyRotation keyRotationPlugin;

    /**
     * Implements callback registration and notification capabilities; the used
     * to process messages received from the mirror. Users instantiate the
     * object and register observer callback methods {@link #addObserver(com.hedera.hcs.sxc.interfaces.HCSCallBackToAppInterface)
     * }
     * to receive processed messages in their apps.
     *
     * End users should only register observers, the remaining public interface
     * is used for low level and background message processing: when a message
     * is received from the mirror then this object will decide how the low
     * level message should be handled; it either constructs an
     * {@link ApplicationMessage} by composing message chunks with {@link #pushUntilCompleteMessage(com.hedera.hcs.sxc.proto.ApplicationMessageChunk, com.hedera.hcs.sxc.interfaces.SxcPersistence)
     * }
     * to be passed on registered observers or responds to low level
     * instructions that handle KeyRoatation or message verification requests.
     * Decryption and message integrity is handled automatically where the
     * HCSCore address-book is consulted behind the scenes.
     *
     * @param hcsCore the instantiated core object. {
     * @see HCSCore}
     * @throws Exception
     */
    public OnHCSMessageCallback (HCSCore hcsCore) throws Exception {
        this.hcsCore = hcsCore;

        this.signMessagesFromCore = hcsCore.getSignMessages();
        this.encryptMessagesFromCore = hcsCore.getEncryptMessages();
        this.rotateKeysFromCore = hcsCore.getRotateKeys();
        this.topics = hcsCore.getTopics();

        if(this.signMessagesFromCore){
            // test signature even if things not encrypted

        }


        if(this.rotateKeysFromCore){
            Class<?> messageKeyRotationClass = Plugins.find("com.hedera.hcs.sxc.plugin.encryption.*", "com.hedera.hcs.sxc.interfaces.SxcKeyRotation", true);
            this.keyRotationPlugin = (SxcKeyRotation)messageKeyRotationClass.newInstance();
        }

        if (this.hcsCore.getCatchupHistory()) {
            log.debug("catching up with mirror history");
            Optional<Instant> lastConsensusTimestamp = Optional.of(this.hcsCore.getPersistence().getLastConsensusTimestamp());
            this.hcsCore.getMirrorSubscription().init(this, this.hcsCore.getApplicationId(), lastConsensusTimestamp, this.hcsCore.getMirrorAddress(), this.hcsCore.getConsensusTopicIds());
        } else {
            log.debug("NOT catching up with mirror history");
            this.hcsCore.getMirrorSubscription().init(this, this.hcsCore.getApplicationId(), Optional.of(Instant.now()), this.hcsCore.getMirrorAddress(), this.hcsCore.getConsensusTopicIds());
        }
    }


    /**
     * Adds an observer to the list of observers. An observer is a
     * call-back function that listens and handles incoming high level
     * application messages.
     * @param listener callback method; a provided parameter that implements the
     * functional interface is {@link HCSResponse} and an example usage is
     * <pre>
    o.addObserver((HCSResponse hcsResponse) -&gt; {
    System.out.print(hcsResponse.getApplicationMessageID());
    });
     * </pre>
     * which prints the id of the application message. Notice that HCSResponse
     * does not return
     * the entire {@link ApplicationMessage} or HCS information. Such information
     * can be obtained from the local store using implementations of {@link
     * SxcPersistence#getApplicationMessageEntity(java.lang.String)
     */
    @Override
    public void addObserver(HCSCallBackToAppInterface listener) {
        observers.add(listener);
    }


    /**
     * Notifies all observers with the supplied message
     * @param message
     * @param applicationMessageId
     */
    @Override
    public void notifyObservers(SxcConsensusMessage sxcConsensusMessage, byte[] message, ApplicationMessageID applicationMessageId) {
        HCSResponse hcsResponse = new HCSResponse();
        hcsResponse.setApplicationMessageID(applicationMessageId);
        hcsResponse.setMessage(message);
        observers.forEach(listener -> listener.onMessage(sxcConsensusMessage, hcsResponse));
    }


    @Override
    public void storeMirrorResponse(SxcConsensusMessage consensusMessage) {
        hcsCore.getPersistence().storeMirrorResponse(consensusMessage);
    }

    @Override
    public void partialMessage(ApplicationMessageChunk messagePart, SxcConsensusMessage sxcConsensusMesssage) {

        try {
            Optional<ApplicationMessage> messageEnvelopeOptional =
                    pushUntilCompleteMessage(messagePart, this.hcsCore.getPersistence());

            if (messageEnvelopeOptional.isPresent()){ // is present if all parts received

                ApplicationMessage appMessage = messageEnvelopeOptional.get();

                System.out.println(appMessage.getEncryptionRandom());
                if(this.encryptMessagesFromCore  // configuration wants encryption
                        || ( ! appMessage.getEncryptionRandom().isEmpty())  // configuration may not want encryption but message can still be encrypted
                ){

                    try {

                        messageEncryptionClass = Plugins.find("com.hedera.hcs.sxc.plugin.encryption.*", "com.hedera.hcs.sxc.interfaces.SxcMessageEncryption", true);
                        this.messageEncryptionPlugin = (SxcMessageEncryption)messageEncryptionClass.newInstance();


                        String applicationMessageId =
                                SxcPersistence.extractApplicationMessageStringId(appMessage.getApplicationMessageId());

                        // check if the message was stored on outgoing and test if it was sent by me
                        SxcApplicationMessageInterface applicationMessageEntity = this.hcsCore.getPersistence().getApplicationMessageEntity(applicationMessageId);

                        // when consensus data is missing then we know that the message was stored when outgoing
                        boolean wasMessageSentByMe = applicationMessageEntity != null && applicationMessageEntity.getLastChronoPartConsensusTimestamp() == null;

                        if (wasMessageSentByMe){  // this is a message I just sent out myself
                            log.debug("Mirror notification with message I sent");
                            // the message is not encrypted; check if it's good and just add missing consensus information store it back and notify observers that it has come back
                            ApplicationMessage clearTextAppMessage = ApplicationMessage.parseFrom(applicationMessageEntity.getApplicationMessage());

                            //test if the message is `good`
                            byte[] shaClrTxt = Hashing.sha(
                                    StringUtils.byteArrayToHexString(
                                            clearTextAppMessage.getBusinessProcessMessage().toByteArray()
                                    )
                            );
                            byte[] signShaClrTxt = Signing.sign(shaClrTxt, hcsCore.getMessageSigningKey());

                            if (! Arrays.equals(signShaClrTxt, appMessage.getBusinessProcessSignatureOnHash().toByteArray())){
                                log.error("Illegal message detected, not processing ...");
                            } else {
                                //message is `good` store it  back with consensus data applied to it.
                                this.hcsCore.getPersistence().storeApplicationMessage(
                                        //TODO Add addressee
                                        ApplicationMessage.parseFrom(applicationMessageEntity.getApplicationMessage()) ,
                                        sxcConsensusMesssage.consensusTimestamp,
                                        StringUtils.byteArrayToHexString(sxcConsensusMesssage.runningHash),
                                        sxcConsensusMesssage.sequenceNumber
                                );
                                notifyObservers(sxcConsensusMesssage, clearTextAppMessage.getBusinessProcessMessage().toByteArray(), clearTextAppMessage.getApplicationMessageId());
                            }
                        } else { // the message was not sent by me
                            // I need to loop through the addressbook and
                            // if the message was sent to me then I need to find who sent it to me  and find the shared key to decrypt it.
                            log.debug("Mirror notification with message I didn't send");

                            boolean messageIsForMe = false;

                            byte[] decryptedBPM  = null;
                            // loop through signatures in address book and keep
                            // the ones that pass verification. Then test to see
                            // if you can decrypt
                            byte[] sharedKey = null;
                            String originAppId = "";

                            for(String appId : hcsCore.getPersistence().getAddressList().keySet() ){
                                Map<String, String> keyMap = hcsCore.getPersistence().getAddressList().get(appId);
                                Ed25519PublicKey theirPubKey = Ed25519PublicKey.fromString(
                                        keyMap.get("theirEd25519PubKeyForSigning"));
                                if (
                                    Signing.verify(
                                            appMessage.getUnencryptedBusinessProcessMessageHash().toByteArray(),
                                            appMessage.getBusinessProcessSignatureOnHash().toByteArray(),

                                                theirPubKey)
                                ){
                                    log.debug("Signature verification on message passed with " + appId + ", message is from them.");
                                    try {
                                        String key = keyMap.get("sharedSymmetricEncryptionKey");
                                        sharedKey = StringUtils.hexStringToByteArray(key);
                                        originAppId = appId;
                                        log.debug("Decrypting message with key " + key.substring(key.length()-10, key.length()-1));
                                        EncryptedData encryptedData = new EncryptedData();
                                        encryptedData.setEncryptedData(appMessage.getBusinessProcessMessage().toByteArray());
                                        encryptedData.setRandom(appMessage.getEncryptionRandom().toByteArray());
                                        decryptedBPM = this.messageEncryptionPlugin.decrypt(sharedKey, encryptedData);
                                        //test if the message is illegal
                                        byte[] shaClrTxt = Hashing.sha(
                                                StringUtils.byteArrayToHexString(decryptedBPM)
                                        );
                                        if (! Hashing.matchSHA(shaClrTxt, appMessage.getUnencryptedBusinessProcessMessageHash().toByteArray())){
                                            log.error("Corrupt message detected.");
                                            throw new Exception("Corrupt message detected.");
                                        }
                                        log.debug("Able to decrypt message");
                                        messageIsForMe = true;
                                        break;
                                    } catch (Exception e){
                                        log.debug("Unable to decrypt message");
                                        continue;
                                    }
                                }
                            }


                            if (messageIsForMe){
                                log.debug("Message is for me, parsing");
                                try  { Any any = Any.parseFrom(decryptedBPM); // if fails goto catch block - TODO, use typing to avoid control flow
                                    // if succeeds then it is
                                    if(any.is(KeyRotationInitialise.class)){  //======= KR1==========================================
                                        // an init message has arrived
                                        // check if it was me who's the initiator. Skip msg if so.
                                        boolean isMeInitiator = hcsCore.getTempKeyAgreement() != null;

                                        if ( ! isMeInitiator ) {
                                            // do not remove commented section
                                            /* changed due to encryption taken from address book
                                            KeyRotationInitialise kr1 = any.unpack(KeyRotationInitialise.class);
                                            byte[] initiatorPublicKeyEncoded = kr1.getInitiatorPublicKeyEncoded().toByteArray();
                                            // create your own new key store it and respond
                                            Pair<byte[], byte[]> respond = keyRotationPlugin.respond(initiatorPublicKeyEncoded);
                                            byte[] newPublicKey =  respond.getLeft();
                                            byte[] newSecretKey = respond.getRight();
                                            byte[] oldSecretKey = hcsCore.getMessageEncryptionKey();
                                            hcsCore.updateSecretKey(newSecretKey);
                                            hcsCore.getPersistence().storeSecretKey(newSecretKey);
                                            KeyRotationRespond kr2 = KeyRotationRespond.newBuilder()
                                                .setResponderPublicKeyEncoded(ByteString.copyFrom(newPublicKey))
                                                .build();
                                            // prepare the response and send it over to the initiator
                                            Any anyPack = Any.pack(kr2);
                                            byte[] encryptedAnyPackedChunkBody = messageEncryptionPlugin.encrypt(oldSecretKey, anyPack.toByteArray());
                                            TransactionId transactionId = new TransactionId(hcsCore.getOperatorAccountId());
                                            ApplicationMessageID newAppId = ApplicationMessageID.newBuilder()
                                                .setAccountID(AccountID.newBuilder()
                                                        .setShardNum(transactionId.accountId.shard)
                                                        .setRealmNum(transactionId.accountId.realm)
                                                        .setAccountNum(transactionId.accountId.account)
                                                        .build()
                                                )
                                                .setValidStart(Timestamp.newBuilder()
                                                        .setSeconds(transactionId.validStart.getEpochSecond())
                                                        .setNanos(transactionId.validStart.getNano())
                                                        .build()
                                                ).build();
                                            ApplicationMessageChunk appChunk = ApplicationMessageChunk.newBuilder()
                                                .setApplicationMessageId(newAppId)
                                                .setChunkIndex(1)
                                                .setChunksCount(1)
                                                .setMessageChunk(
                                                    //fit an antire ApplicationMessage in the chunk and set body message to the encrypted stuff
                                                    ApplicationMessage.newBuilder()
                                                        .setApplicationMessageId(newAppId)
                                                        //TODO: set signature
                                                        .setBusinessProcessMessage(ByteString.copyFrom(encryptedAnyPackedChunkBody))
                                                        //TODO: set hash
                                                        .build()
                                                        .toByteString()
                                            ).build();
                                            ConsensusMessageSubmitTransaction txRotation = new ConsensusMessageSubmitTransaction()
                                                .setMessage(appChunk.toByteArray())
                                                .setTopicId(this.topics.get(
                                                        0 // TODO get the topic from the appMessage
                                                ).getConsensusTopicId())
                                                .setTransactionId(transactionId);
                                            // submit to network
                                            try (Client client = new Client(hcsCore.getNodeMap())) {
                                                client.setOperator(
                                                        hcsCore.getOperatorAccountId(),
                                                         hcsCore.getEd25519PrivateKey()
                                                );
                                                client.setMaxTransactionFee(hcsCore.getMaxTransactionFee());
                                                TransactionId txIdKR2 =  txRotation.execute(client);
                                                TransactionReceipt receiptKR2 = txIdKR2.getReceipt(client, Duration.ofSeconds(30));
                                            } catch (HederaStatusException ex) {
                                                    log.error(ex);
                                            } catch (HederaNetworkException ex) {
                                                log.error(ex);
                                            }
                                            */
                                        } // test checking if initiator
                                    } // end any test initialise

                                    else if(any.is(KeyRotationRespond.class)){ // ============ KR2 =====================================================
                                        // do not remove commented section
                                        /*
                                        // a respond message has arrived
                                        // check if it was me who sent respond message. Skip msg if so.
                                        boolean isMeResponder = hcsCore.getTempKeyAgreement() != null;
                                        if ( isMeResponder ) {
                                            KeyRotationRespond kr2 = any.unpack(KeyRotationRespond.class);
                                            byte[] responderPublicKeyEncoded = kr2.getResponderPublicKeyEncoded().toByteArray();
                                            // get the keyAgreement from core
                                            KeyAgreement keyAgreement = hcsCore.getTempKeyAgreement();
                                            hcsCore.setTempKeyAgreement(null);
                                            byte[] newSecretKey = keyRotationPlugin.finalise(responderPublicKeyEncoded, keyAgreement);
                                              // store new SecretKey in Database
                                           hcsCore.updateSecretKey(newSecretKey);
                                           hcsCore.getPersistence().storeSecretKey(newSecretKey);
                                        }*/
                                    } else if (any.is(RequestProof.class)) {

                                        RequestProof requestProof = any.unpack(RequestProof.class);
                                        // prove the message. if OK, send back an OK message (set the `appMessage`) , don't save this
                                        // prepare the return type
                                        ConfirmProof.Builder proofResults = ConfirmProof.newBuilder();

                                        List<VerifiableMessage> verifiableMessageList = requestProof.getApplicationMessageList();

                                        for (VerifiableMessage verifiableMessage :  verifiableMessageList ){
                                            //VerifiableMessage verifiableMessage =  requestProof.getApplicationMessage(0);
                                            if (verifiableMessage.hasVerifiableApplicationMessage()){
                                                VerifiableApplicationMessage verifiableApplicationMessage = verifiableMessage.getVerifiableApplicationMessage();
                                                VerifiedMessage verifiedMessage = VerifiedMessage
                                                        .newBuilder()
                                                        .setVerificationOutcome(prove(verifiableApplicationMessage))
                                                        .setApplicationMessage(verifiableMessage)
                                                        .build();
                                                proofResults.addProof(verifiedMessage);
                                            } else {
                                                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
                                            }
                                        }
                                        ConfirmProof cf = proofResults.build();

                                        Any anyPack = Any.pack(cf);


                                       //send it back to whoever you got it from

                                       OutboundHCSMessage o =  new OutboundHCSMessage(hcsCore);
                                       o.restrictTo(originAppId).sendMessage(0, anyPack.toByteArray());

                                       /*
                                       EncryptedData encrypt = messageEncryptionPlugin.encrypt(sharedKey, anyPack.toByteArray());
                                        byte[] encryptedAnyPackedChunkBody = encrypt.getEncryptedData();
                                        TransactionId transactionId = new TransactionId(hcsCore.getOperatorAccountId());
                                        ApplicationMessageID newAppId = ApplicationMessageID.newBuilder()
                                            .setAccountID(AccountID.newBuilder()
                                                    .setShardNum(transactionId.accountId.shard)
                                                    .setRealmNum(transactionId.accountId.realm)
                                                    .setAccountNum(transactionId.accountId.account)
                                                    .build()
                                            )
                                            .setValidStart(Timestamp.newBuilder()
                                                    .setSeconds(transactionId.validStart.getEpochSecond())
                                                    .setNanos(transactionId.validStart.getNano())
                                                    .build()
                                            ).build();
                                        byte [] hashOfUnencryptedBusinessMessage = Hashing.sha(
                                                StringUtils.byteArrayToHexString(
                                                        anyPack.toByteArray()
                                                )
                                        );
                                        ApplicationMessageChunk appChunk = ApplicationMessageChunk.newBuilder()
                                            .setApplicationMessageId(newAppId)
                                            .setChunkIndex(1)
                                            .setChunksCount(1)
                                            .setMessageChunk(
                                                //fit an antire ApplicationMessage in the chunk and set body message to the encrypted stuff
                                                ApplicationMessage.newBuilder()
                                                    .setApplicationMessageId(newAppId)
                                                    .setBusinessProcessMessage(ByteString.copyFrom(encryptedAnyPackedChunkBody))
                                                    .setUnencryptedBusinessProcessMessageHash(ByteString.copyFrom(hashOfUnencryptedBusinessMessage))
                                                    .setBusinessProcessSignatureOnHash(ByteString.copyFrom(Signing.sign(hashOfUnencryptedBusinessMessage, hcsCore.getMessageSigningKey())))
                                                    .setEncryptionRandom(ByteString.copyFrom(encrypt.getRandom()))
                                                    .build()
                                                    .toByteString()
                                        ).build();
                                        ConsensusMessageSubmitTransaction txVerifiedMessage = new ConsensusMessageSubmitTransaction()
                                            .setMessage(appChunk.toByteArray())
                                            .setTopicId(this.topics.get(
                                                    0 // TODO get the topic from the appMessage
                                            ).getConsensusTopicId())
                                            .setTransactionId(transactionId);
                                        // submit to network
                                        try (Client client = new Client(hcsCore.getNodeMap())) {
                                            client.setOperator(
                                                    hcsCore.getOperatorAccountId(),
                                                    hcsCore.getEd25519PrivateKey()
                                            );
                                            client.setMaxTransactionFee(hcsCore.getMaxTransactionFee());
                                            TransactionId txIdVMessage =  txVerifiedMessage.execute(client);
                                            TransactionReceipt receiptIdVMessage = txIdVMessage.getReceipt(client, Duration.ofSeconds(30));
                                        } catch (Exception ex) {
                                                log.error(ex);
                                        }
                                    */


                                    /*
                                    } else if (any.is(VerifiedMessage.class)) {
                                        ApplicationMessage decryptedAppmessage = ApplicationMessage.newBuilder()
                                            .setApplicationMessageId(appMessage.getApplicationMessageId())
                                            .setUnencryptedBusinessProcessMessageHash(appMessage.getUnencryptedBusinessProcessMessageHash())
                                            .setBusinessProcessMessage(
                                                    ByteString.copyFrom(decryptedBPM)
                                             )
                                            .setBusinessProcessSignatureOnHash(appMessage.getBusinessProcessSignatureOnHash())
                                            .build();
                                        appMessage = decryptedAppmessage;
                                        this.hcsCore.getPersistence().storeApplicationMessage(
                                                appMessage,
                                                sxcConsensusMesssage.consensusTimestamp,
                                                StringUtils.byteArrayToHexString(sxcConsensusMesssage.runningHash),
                                                sxcConsensusMesssage.sequenceNumber
                                        ); */

                                    } else { // the message is not a KR or PROOF instruction. It is some other PROTO message
                                               // send back the BPM; IF it's not a PROTO message then use the CATCH
                                               // block. TODO, rewrite to avoid trycatch controll flow
                                        ApplicationMessage decryptedAppmessage = ApplicationMessage.newBuilder()
                                            .setApplicationMessageId(appMessage.getApplicationMessageId())
                                            .setUnencryptedBusinessProcessMessageHash(appMessage.getUnencryptedBusinessProcessMessageHash())
                                            .setBusinessProcessMessage(
                                                    ByteString.copyFrom(decryptedBPM)
                                             )
                                            .setBusinessProcessSignatureOnHash(appMessage.getBusinessProcessSignatureOnHash())
                                            .build();
                                        appMessage = decryptedAppmessage;
                                        this.hcsCore.getPersistence().storeApplicationMessage(
                                                appMessage,
                                                sxcConsensusMesssage.consensusTimestamp,
                                                StringUtils.byteArrayToHexString(sxcConsensusMesssage.runningHash),
                                                sxcConsensusMesssage.sequenceNumber
                                        );
                                    }

                                }
                                catch(InvalidProtocolBufferException e){  // IF NOT PROTO: the message is not a PROTO message. It's sometihng else
                                    ApplicationMessage decryptedAppmessage = ApplicationMessage.newBuilder()
                                            .setApplicationMessageId(appMessage.getApplicationMessageId())
                                            .setUnencryptedBusinessProcessMessageHash(appMessage.getUnencryptedBusinessProcessMessageHash())
                                            .setBusinessProcessMessage(
                                                    ByteString.copyFrom(decryptedBPM)
                                            )
                                            .setBusinessProcessSignatureOnHash(appMessage.getBusinessProcessSignatureOnHash())

                                            .build();
                                        appMessage = decryptedAppmessage;
                                        this.hcsCore.getPersistence().storeApplicationMessage(
                                                decryptedAppmessage,
                                                sxcConsensusMesssage.consensusTimestamp,
                                                StringUtils.byteArrayToHexString(sxcConsensusMesssage.runningHash),
                                                sxcConsensusMesssage.sequenceNumber
                                        );

                                } finally {
                                    notifyObservers(
                                            sxcConsensusMesssage
                                            ,  appMessage.getBusinessProcessMessage().toByteArray()
                                            , appMessage.getApplicationMessageId());
                                }
                            } else { // the message was encrypted and not sent to me.
                                     // persist it encrypted, you may still need it in an proof request
                                    log.debug("Received enrypted message but it's not for me");
                                    this.hcsCore.getPersistence().storeApplicationMessage(
                                            messageEnvelopeOptional.get(),
                                            sxcConsensusMesssage.consensusTimestamp,
                                            StringUtils.byteArrayToHexString(sxcConsensusMesssage.runningHash),
                                            sxcConsensusMesssage.sequenceNumber
                                    );
                            }

                        }

                    } catch (Exception e){
                        e.printStackTrace();
                    }
                }else { // not encrypted
                    log.debug("Received clear text message");
                    this.hcsCore.getPersistence().storeApplicationMessage(
                            messageEnvelopeOptional.get(),
                            sxcConsensusMesssage.consensusTimestamp,
                            StringUtils.byteArrayToHexString(sxcConsensusMesssage.runningHash),
                            sxcConsensusMesssage.sequenceNumber
                    );
                    notifyObservers(sxcConsensusMesssage, appMessage.getBusinessProcessMessage().toByteArray(), appMessage.getApplicationMessageId());
                }

            } else { // message envelope not present
                // do nothing - there are still parts that need to be collected.
            }

        } catch (InvalidProtocolBufferException ex) {
            log.error(ex);
        }
    }



    /**
     * Adds ApplicationMessageChunk into memory and returns
     * a fully combined / assembled ApplicationMessage if all parts are present
     * @param messageChunk a chunked message received from the queue
     * @param persistence the memory. The object is side-effected with each
     * function invocation.
     * @return a fully combined / assembled ApplicationMessage if all parts present,
     * nothing otherwise.
     * @throws InvalidProtocolBufferException
     */
    static Optional<ApplicationMessage> pushUntilCompleteMessage(ApplicationMessageChunk messageChunk, SxcPersistence persistence) throws InvalidProtocolBufferException {

        ApplicationMessageID applicationMessageId = messageChunk.getApplicationMessageId();
        //look up db to find parts received already
        List<ApplicationMessageChunk> chunkList = persistence.getParts(applicationMessageId);
        if (chunkList==null){
            chunkList = new ArrayList<ApplicationMessageChunk>();
            chunkList.add(messageChunk);
        } else {
            chunkList.add(messageChunk);
        }
        persistence.putParts(applicationMessageId, chunkList);

        if (messageChunk.getChunksCount() == 1){ // if it's only one then an entire ApplicationMessage did fit in the MessageChunk
            ApplicationMessage applicationMessage = ApplicationMessage.parseFrom(messageChunk.getMessageChunk());
            return  Optional.of( applicationMessage);
        } else if (chunkList.size() == messageChunk.getChunksCount()) { // all parts received
            // sort by part id
            chunkList.sort(Comparator.comparingInt(ApplicationMessageChunk::getChunkIndex));
            // merge down
            ByteString merged =
                    chunkList.stream()
                            .map(ApplicationMessageChunk::getMessageChunk)
                            .reduce(ByteUtil::merge).get();
            // construct envelope from merged array. TODO: if fail
            ApplicationMessage messageEnvelope = ApplicationMessage.parseFrom(merged);
            return  Optional.of(messageEnvelope);
        } else { // not all parts received yet
            return Optional.empty();
        }
    }

    private VerifiedMessage.VerificationOutcome prove(VerifiableApplicationMessage verifiableApplicationMessage) throws NoSuchAlgorithmException, InvalidProtocolBufferException {
        SxcApplicationMessageInterface applicationMessageEntity = hcsCore.getPersistence()
                .getApplicationMessageEntity(
                    SxcPersistence.extractApplicationMessageStringId(verifiableApplicationMessage.getApplicationMessageId()
                )
        );

        if (applicationMessageEntity == null){
            return VerifiedMessage.VerificationOutcome.UNABLE_TO_VERIFY;
        }

        ByteString originalBusinessProcessMessage = verifiableApplicationMessage.getOriginalBusinessProcessMessage();
        byte[] hashOfVerifiable = Hashing.sha(
                StringUtils.byteArrayToHexString(
                        originalBusinessProcessMessage.toByteArray()
                )
        );
        ApplicationMessage appMessage = ApplicationMessage.parseFrom(applicationMessageEntity.getApplicationMessage());
        byte[] messageOfStored = appMessage.getBusinessProcessMessage().toByteArray();
        byte[] hashOfStored = appMessage.getUnencryptedBusinessProcessMessageHash().toByteArray();
        byte[] signatureOfStored = appMessage.getBusinessProcessSignatureOnHash().toByteArray();
        Ed25519PublicKey publicKey = Ed25519PublicKey.fromBytes(verifiableApplicationMessage.getSenderPublicSigningKey().toByteArray());
        if (! Hashing.matchSHA(hashOfStored, hashOfVerifiable))  {return VerifiedMessage.VerificationOutcome.HASH_DOES_NOT_MATCH;}
        else if (! Signing.verify(hashOfStored, signatureOfStored, publicKey)) {return VerifiedMessage.VerificationOutcome.SIGNATURE_DOES_NOT_MATCH;}
        else {return VerifiedMessage.VerificationOutcome.VERIFIED_OK;}
    }
}
