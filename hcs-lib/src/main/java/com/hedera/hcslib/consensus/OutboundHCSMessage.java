package com.hedera.hcslib.consensus;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.HederaException;
import com.hedera.hashgraph.sdk.HederaNetworkException;
import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hashgraph.sdk.TransactionReceipt;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.consensus.SubmitMessageTransaction;
import com.hedera.hashgraph.sdk.consensus.TopicId;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PrivateKey;
import com.hedera.hcslib.HCSLib;
import com.hedera.hcslib.proto.java.CompleteMessage;
import com.hedera.hcslib.proto.java.MessageId;
import com.hedera.hcslib.proto.java.MessagePart;
import java.util.Arrays;

public final class OutboundHCSMessage {
    private boolean signMessages = false;
    private boolean encryptMessages = false;
    private boolean rotateKeys = false;
    private int rotationFrequency = 0;
    private Map<AccountId, String> nodeMap = new HashMap<AccountId, String>();
    private AccountId operatorAccountId = new AccountId(0, 0, 0); 
    private Ed25519PrivateKey ed25519PrivateKey;
    private List<TopicId> topicIds = new ArrayList<TopicId>();
    
    public OutboundHCSMessage(HCSLib hcsLib) {
        this.signMessages = hcsLib.getSignMessages();
        this.encryptMessages = hcsLib.getEncryptMessages();
        this.rotateKeys = hcsLib.getRotateKeys();
        this.nodeMap = hcsLib.getNodeMap();
        this.operatorAccountId = hcsLib.getOperatorAccountId();
        this.ed25519PrivateKey = hcsLib.getEd25519PrivateKey();
        this.topicIds = hcsLib.getTopicIds();
    }

    public OutboundHCSMessage overrideMessageSignature(boolean signMessages) {
        this.signMessages = signMessages;
        return this;
    }
    public OutboundHCSMessage overrideEncryptedMessages(boolean encryptMessages) {
        this.encryptMessages = encryptMessages;
        return this;
    }
    public OutboundHCSMessage overrideKeyRotation(boolean keyRotation, int frequency) {
        this.rotateKeys = keyRotation;
        this.rotationFrequency = frequency;
        return this;
    }
    public OutboundHCSMessage overrideNodeMap(Map<AccountId, String> nodeMap) {
        this.nodeMap = nodeMap;
        return this;
    }
    public OutboundHCSMessage overrideOperatorAccountId(AccountId operatorAccountId) {
        this.operatorAccountId = operatorAccountId;
        return this;
    }
    public OutboundHCSMessage overrideOperatorKey(Ed25519PrivateKey ed25519PrivateKey) {
        this.ed25519PrivateKey = ed25519PrivateKey;
        return this;
    }
    /** Sends a single cleartext message
     * 
     * @param topicIndex the index reference in one of {@link #topicIds}
     * @param message
     * @return
     * @throws HederaNetworkException
     * @throws IllegalArgumentException
     * @throws HederaException 
     */
    public boolean sendMessage(int topicIndex, String message) throws HederaNetworkException, IllegalArgumentException, HederaException {

        if (signMessages) {
            
        }
        if (encryptMessages) {
            
        }
        if (rotateKeys) {
            int messageCount = 0; //TODO - keep track of messages app-wide, not just here.
            if (messageCount > rotationFrequency) {
            }
        }

        // sends a message to HCS
        Client client = new Client(this.nodeMap);
        client.setOperator(
            this.operatorAccountId
            ,this.ed25519PrivateKey
        );
        client.setMaxTransactionFee(100_000_000L);

        // generate TXId for main and first message
        TransactionId transactionId = new TransactionId(this.operatorAccountId);
        
        // create complete message (from app provided data) in protobuf (CompleteMessage)
            //-> TxId
            //-> Complete App Message (ByteArray) (bytes in protobuf -> ByteString in java)

        MessageId completeMessageId = MessageId.newBuilder()
                .setFromAccountShardNum(transactionId.getAccountId().getShardNum())
                .setFromAccountRealmNum(transactionId.getAccountId().getRealmNum())
                .setFromAccountNum(transactionId.getAccountId().getAccountNum())
                .setSeconds(transactionId.getValidStart().getEpochSecond())
                .setNanos(transactionId.getValidStart().getNano())
                .build();
         
        
        byte[] originalMessage = message.getBytes();
        
        CompleteMessage completeMessage = CompleteMessage
                .newBuilder()
                .setCompleteMessageId(completeMessageId)
                .setCompleteMessage(ByteString.copyFrom(originalMessage))
                .build();
        
        
        // convert above protobuf message to byte array
        final int originalMessageSize = originalMessage.length;
        
        // break up byte array into 3500 bytes parts
        // protobuf message part objects (iterate) -> MessagePart
        // include above part(s) into each
        // -> (?) convert to byte array and send to HH
        
        
        final int chunkSize = 3500; // the hcs tx limit is 4k - there are header bytes that will be added to that
        
        for(int i=0; i < originalMessageSize; i += chunkSize){
        
            byte[] originalMessageChunk = Arrays.copyOfRange(
                    originalMessage,
                    i,
                    Math.min(originalMessageSize, i + chunkSize)
            );
            
            MessagePart messagePart = MessagePart.newBuilder()
                    .setCompleteMessageId(completeMessageId)
                    .setPartId(i)
                    .setPartsTotal(originalMessageSize / chunkSize)
                    .setMessagePart(ByteString.copyFrom(originalMessageChunk))
                    .build();
            
            TransactionReceipt receipt = new SubmitMessageTransaction(client)
                .setMessage(messagePart.toByteArray())
                .setTopicId(this.topicIds.get(topicIndex))
                .setTransactionId(transactionId)
                .executeForReceipt();
                System.out.println(receipt.getTopicSequenceNumber());
            
        }     
        return true;
    }
}
