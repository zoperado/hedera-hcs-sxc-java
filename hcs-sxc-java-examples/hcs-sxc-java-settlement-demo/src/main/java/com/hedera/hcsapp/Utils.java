package com.hedera.hcsapp;

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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hcsapp.entities.Credit;
import com.hedera.hcsapp.entities.Settlement;
import com.hedera.hcsapp.restclasses.SettlementRest;
import com.hedera.hcs.sxc.proto.AccountID;
import com.hedera.hcs.sxc.proto.ApplicationMessageID;
import com.hedera.hcs.sxc.proto.Timestamp;

import proto.CreditBPM;
import proto.Money;
import proto.SettleProposeBPM;

public final class Utils {
    private static Random random = new Random();

    public static String timestampToDate(long seconds, int nanos) {
        LocalDateTime dateTime = LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        String formattedDate = dateTime.format(formatter);
        return formattedDate;
    }
    
    public static String timestampToTime(long seconds, int nanos) {
        LocalDateTime dateTime = LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("H:mm", Locale.getDefault());
        String formattedDate = dateTime.format(formatter);
        return formattedDate;
    }
    
    public static String applicationMessageIdToString(ApplicationMessageID transactionId) {
        String txId = "0.0." + transactionId.getAccountID().getAccountNum()
                + "-" + transactionId.getValidStart().getSeconds()
                + "-" + transactionId.getValidStart().getNanos();
        return txId;
    }
    
    public static String transactionIdToString(TransactionId transactionId) {
        String txId = "0.0." + transactionId.accountId.account
                + "-" + transactionId.validStart.getEpochSecond()
                + "-" + transactionId.validStart.getNano();
        return txId;
    }
    
    public static Credit creditFromCreditBPM(CreditBPM creditBPM, String threadId) {
        Credit credit = new Credit();
        
        credit.setAdditionalNotes(creditBPM.getAdditionalNotes());
        credit.setPayerName(creditBPM.getPayerName());
        credit.setRecipientName(creditBPM.getRecipientName());
        credit.setReference(creditBPM.getServiceRef());
        credit.setApplicationMessageId(creditBPM.getApplicationMessageID());
        credit.setCreatedDate(creditBPM.getCreatedDate());
        credit.setCreatedTime(creditBPM.getCreatedTime());
        credit.setAmount(creditBPM.getValue().getUnits());
        credit.setCurrency(creditBPM.getValue().getCurrencyCode());
        credit.setThreadId(threadId);
        
        return credit;
    }
    
    public static CreditBPM creditBPMFromCredit(Credit credit) {
        Money value = Money.newBuilder()
                .setCurrencyCode(credit.getCurrency())
                .setUnits(credit.getAmount())
                .build();

        CreditBPM creditBPM = CreditBPM.newBuilder()
                .setAdditionalNotes(credit.getAdditionalNotes())
                .setPayerName(credit.getPayerName())
                .setRecipientName(credit.getRecipientName())
                .setServiceRef(credit.getReference())
                .setApplicationMessageID(credit.getApplicationMessageId())
                .setCreatedDate(credit.getCreatedDate())
                .setCreatedTime(credit.getCreatedTime())
                .setValue(value)
                .build();

        return creditBPM;
    }

    public static Settlement settlementFromSettleProposeBPM(SettleProposeBPM settleProposeBPM, String threadId) {
        Settlement settlement = new Settlement();
        
        settlement.setAdditionalNotes(settleProposeBPM.getAdditionalNotes());
        settlement.setCurrency(settleProposeBPM.getNetValue().getCurrencyCode());
        settlement.setNetValue(settleProposeBPM.getNetValue().getUnits());
        settlement.setPayerName(settleProposeBPM.getPayerName());
        settlement.setRecipientName(settleProposeBPM.getRecipientName());
        settlement.setThreadId(threadId);
        settlement.setCreatedDate(settleProposeBPM.getCreatedDate());
        settlement.setCreatedTime(settleProposeBPM.getCreatedTime());
        
        return settlement;
    }
    public static ApplicationMessageID applicationMessageIdFromString(String appMessageId) {
        String[] messageIdParts = appMessageId.split("-");
        String[] account = messageIdParts[0].split("\\.");
        
        AccountID accountId = AccountID.newBuilder()
                .setShardNum(Long.parseLong(account[0]))
                .setRealmNum(Long.parseLong(account[1]))
                .setAccountNum(Long.parseLong(account[2]))
                .build();
        
        Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(Long.parseLong(messageIdParts[1]))
                .setNanos(Integer.parseInt(messageIdParts[2]))
                .build();
        
        ApplicationMessageID applicationMessageId = ApplicationMessageID.newBuilder()
                .setAccountID(accountId)
                .setValidStart(timestamp)
                .build();
        
        return applicationMessageId;
    }
    
    public static String getThreadId() {
        Instant now = Instant.now();
        long nano = now.getNano();
        
        long remainder = nano - (nano / 1000 * 1000); // check nanos end with 000.
        if (remainder == 0) {
            int rndNano = random.nextInt(1000);
            nano = nano + rndNano;
        }
     
        return now.getEpochSecond() + "-" + nano;
    }
    public static Money moneyFromSettlement(Settlement settlement) {
        return Money.newBuilder().setCurrencyCode(settlement.getCurrency())
                .setUnits(settlement.getNetValue()).build();

    }
    public static ResponseEntity<SettlementRest> serverError() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");

        return new ResponseEntity<>(headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
