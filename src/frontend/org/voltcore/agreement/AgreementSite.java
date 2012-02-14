/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltcore.agreement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.jute_voltpatches.BinaryInputArchive;
import org.apache.jute_voltpatches.BinaryOutputArchive;
import org.apache.zookeeper_voltpatches.ZooDefs.OpCode;
import org.apache.zookeeper_voltpatches.server.NIOServerCnxn;
import org.apache.zookeeper_voltpatches.server.Request;
import org.apache.zookeeper_voltpatches.server.ServerCnxn;
import org.apache.zookeeper_voltpatches.server.ZooKeeperServer;
import org.json_voltpatches.JSONObject;
import org.voltcore.TransactionIdManager;
import org.voltcore.VoltDB;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.AgreementTaskMessage;
import org.voltcore.messaging.BinaryPayloadMessage;
import org.voltcore.messaging.FailureSiteUpdateMessage;
import org.voltcore.messaging.HeartbeatMessage;
import org.voltcore.messaging.HeartbeatResponseMessage;
import org.voltcore.messaging.LocalObjectMessage;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.MessagingException;
import org.voltcore.messaging.RecoveryMessage;
import org.voltcore.messaging.Subject;
import org.voltcore.messaging.TransactionInfoBaseMessage;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.utils.MiscUtils;

/*
 * A wrapper around a single node ZK server. The server is a modified version of ZK that speaks the ZK
 * wire protocol and data model, but has no durability. Agreement is provided
 * by the AgreementSite wrapper which contains a restricted priority queue like an execution site,
 * but also has a transaction id manager and a unique initiator id. The intiator ID and site id are the same
 * as the id of the regular txn initiator on this node. The mailbox used has a different ID so messages
 * for agreement are routed here.
 *
 * Recovery is implemented by shipping a complete snapshot at a txnid to the recovering node, then every node
 * ships all the agreement txns they know about to the recovering node.
 */
public class AgreementSite implements org.apache.zookeeper_voltpatches.server.ZooKeeperServer.Callout {

    private static final byte BINARY_PAYLOAD_SNAPSHOT = 0;
    private static final byte BINARY_PAYLOAD_JOIN_REQUEST = 1;

    private static enum RecoveryStage {
        WAITING_FOR_SAFETY,
        SENT_PROPOSAL,
        RECEIVED_SNAPSHOT,
        RECOVERED
    }

    private RecoveryStage m_recoveryStage = RecoveryStage.RECOVERED;
    private final CountDownLatch m_recoveryComplete = new CountDownLatch(1);
    private final ZooKeeperServer m_server;
    private final NIOServerCnxn.Factory m_cnxnFactory;
    private final Mailbox m_mailbox;
    private final TransactionIdManager m_idManager;
    private final RestrictedPriorityQueue m_txnQueue;
    private final long m_hsId;

    /*
     * Not failed sites
     */
    private final TreeSet<Long> m_hsIds = new TreeSet<Long>();
    private final
        HashMap<Long, OrderableTransaction> m_transactionsById = new HashMap<Long, OrderableTransaction>();
    final AgreementTxnIdSafetyState m_safetyState;
    private volatile boolean m_shouldContinue = true;
    private volatile boolean m_recovering = false;
    private static final VoltLogger m_recoveryLog = new VoltLogger("RECOVERY");
    private static final VoltLogger m_agreementLog = new VoltLogger("AGREEMENT");
    private long m_minTxnIdAfterRecovery = Long.MIN_VALUE;
    private final CountDownLatch m_shutdownComplete = new CountDownLatch(1);
    private byte m_recoverySnapshot[] = null;
    private Long m_recoverBeforeTxn = null;
    private Long m_siteRequestingRecovery = null;
    private final InterfaceToMessenger m_messenger;

    /**
     * Failed sites which haven't been agreed upon as failed
     */
    private final HashSet<Long> m_pendingFailedSites = new HashSet<Long>();

    /**
     * Failed sites for which agreement has been reached.
     */
    private final HashSet<Long> m_handledFailedSites = new HashSet<Long>();

    public static final class FaultMessage extends VoltMessage {

        public final long failedSite;

        public FaultMessage(long failedSite) {
            this.failedSite = failedSite;
        }

        @Override
        public int getSerializedSize() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flattenToBuffer(ByteBuffer buf) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void initFromBuffer(ByteBuffer buf) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Store values from older failed nodes. They are repeated with every failure message
     */
    private final HashMap<Long, Long> m_newestSafeTransactionForInitiatorLedger =
        new HashMap<Long, Long>();

    public AgreementSite(
            InterfaceToMessenger messenger,
            long myAgreementHSId,
            Set<Long> agreementHSIds,
            int initiatorId,
            Mailbox mailbox,
            InetSocketAddress address,
            long backwardsTimeForgiveness) throws IOException {
        m_messenger = messenger;
        m_mailbox = mailbox;
        m_hsId = myAgreementHSId;
        m_hsIds.addAll(agreementHSIds);

        m_idManager = new TransactionIdManager( initiatorId, 0, backwardsTimeForgiveness );
        // note, the agreement site always uses the safety dance, even
        // if it could skip it if there was one node
        m_txnQueue =
            new RestrictedPriorityQueue(
                    myAgreementHSId, mailbox, true);
        m_safetyState = new AgreementTxnIdSafetyState(myAgreementHSId);
        for (Long hsId : m_hsIds) {
            m_txnQueue.ensureInitiatorIsKnown(hsId);
            m_safetyState.addState(hsId);
        }

        m_cnxnFactory =
            new NIOServerCnxn.Factory( address, 10);
        m_server = new ZooKeeperServer(this);
        m_handledFailedSites.addAll(m_pendingFailedSites);
        if (agreementHSIds.size() > 1) {
            m_recovering = true;
        }
        if (m_recovering) {
            m_recoveryStage = RecoveryStage.WAITING_FOR_SAFETY;
        } else {
            m_recoveryComplete.countDown();
        }
    }

    public void start() throws InterruptedException, IOException {
        m_cnxnFactory.startup(m_server);
    }

    public void shutdown() throws InterruptedException {
        m_shouldContinue = false;
        m_shutdownComplete.await();
    }

    private void shutdownInternal() {
        m_cnxnFactory.shutdown();
    }


    public void recoveryRunLoop() throws Exception {
        long lastHeartbeatTime = System.currentTimeMillis();
        while (m_recovering && m_shouldContinue) {
            if (m_recoveryStage == RecoveryStage.WAITING_FOR_SAFETY) {
                Long safeTxnId = m_txnQueue.safeToRecover();
                if (safeTxnId != null) {
                    m_recoveryStage = RecoveryStage.SENT_PROPOSAL;
                    m_recoverBeforeTxn = safeTxnId;
                    long sourceHSId = 0;
                    for (Long hsId : m_hsIds) {
                        if (hsId != m_hsId) {
                            sourceHSId = hsId;
                            break;
                        }
                    }
                    RecoveryMessage recoveryMessage =
                        new RecoveryMessage(
                                m_hsId,
                                safeTxnId,
                                new byte[4], -1);
                    m_mailbox.send( sourceHSId, recoveryMessage);
                }
            }

            VoltMessage message = m_mailbox.recvBlocking(5);
            if (message != null) {
                processMessage(message);
            }

            final long now = System.currentTimeMillis();
            if (now - lastHeartbeatTime > 5) {
                lastHeartbeatTime = now;
                sendHeartbeats();
            }

            if (m_recoverBeforeTxn == null) {
                continue;
            }

            if (m_txnQueue.peek() != null && m_txnQueue.peek().txnId < m_recoverBeforeTxn.longValue()) {
                m_transactionsById.remove(m_txnQueue.poll().txnId);
            } else if (m_recoveryStage == RecoveryStage.RECEIVED_SNAPSHOT) {
                processZKSnapshot();
                return;
            }
        }
    }
    @Override
    public void run() {
        try {
            if (m_recovering) {
                recoveryRunLoop();
            }
            long lastHeartbeatTime = System.currentTimeMillis();
            while (m_shouldContinue) {
                VoltMessage message = m_mailbox.recvBlocking(5);
                if (message != null) {
                    processMessage(message);
                }

                final long now = System.currentTimeMillis();
                if (now - lastHeartbeatTime > 5) {
                    lastHeartbeatTime = now;
                    sendHeartbeats();
                }

                if (m_recovering) {
                    continue;
                }

                OrderableTransaction ot = m_txnQueue.poll();
                if (ot != null) {
                    if (m_recoverBeforeTxn != null) {
                        assert(m_recoveryStage == RecoveryStage.RECOVERED);
                        assert(m_recovering == false);
                        assert(m_siteRequestingRecovery != null);
                        if (ot.txnId >= m_recoverBeforeTxn) {
                            shipZKDatabaseSnapshot(m_siteRequestingRecovery, ot.txnId);
                        }
                    }

                    if (ot.txnId < m_minTxnIdAfterRecovery) {
                        String errMsg = "Transaction queue released a transaction from before this " +
                                " node was recovered was complete";
                        VoltDB.crashLocalVoltDB(errMsg, false, null);
                    }
                    m_transactionsById.remove(ot.txnId);

                    if (ot instanceof AgreementRejoinTransactionState) {
                        AgreementRejoinTransactionState txnState = (AgreementRejoinTransactionState)ot;
                        try {
                            processJoin(txnState.m_rejoiningSite);
                        } finally {
                            if (txnState.m_onCompletion != null) {
                                txnState.m_onCompletion.countDown();
                            }
                        }
                    } else if (ot instanceof AgreementTransactionState) {
                        AgreementTransactionState txnState = (AgreementTransactionState)ot;
                        //Owner is what associates the session with a specific initiator
                        //only used for createSession
                        txnState.m_request.setOwner(txnState.initiatorHSId);
                        m_server.prepRequest(txnState.m_request, txnState.txnId);
                    }
                } else if (m_recoverBeforeTxn != null) {
                    assert(m_recoveryStage == RecoveryStage.RECOVERED);
                    assert(m_recovering == false);
                    assert(m_siteRequestingRecovery != null);
                    Long foo = m_txnQueue.safeToRecover();
                    if (foo != null && foo.longValue() >= m_recoverBeforeTxn.longValue()) {
                        shipZKDatabaseSnapshot(m_siteRequestingRecovery, foo);
                    }
                }
            }
        } catch (Exception e) {
            VoltDB.crashLocalVoltDB("Error in agreement site", false, e);
        } finally {
            try {
                shutdownInternal();
            } finally {
                m_shutdownComplete.countDown();
            }
        }
    }

    private void processJoin(long rejoiningAgreementSite) {
        if (m_handledFailedSites.contains(rejoiningAgreementSite)) {
            m_recoveryLog.info(
                    "Igoring join of site " +
                    MiscUtils.hsIdToString(rejoiningAgreementSite) +
                    " because it is known to be failed");
            return;
        }
        m_safetyState.addState(rejoiningAgreementSite);
        m_txnQueue.ensureInitiatorIsKnown(rejoiningAgreementSite);
        m_hsIds.add(rejoiningAgreementSite);
        m_recoveryLog.info("Unfaulting site " + rejoiningAgreementSite + " known failed sites "
                + MiscUtils.hsIdCollectionToString(m_pendingFailedSites) +
                " handled failed sites " +  MiscUtils.hsIdCollectionToString(m_handledFailedSites) +
                " active sites " +  MiscUtils.hsIdCollectionToString(m_hsIds));
    }

    private void sendHeartbeats() {
        long txnId = m_idManager.getNextUniqueTransactionId();
        for (long initiatorId : m_hsIds) {
            HeartbeatMessage heartbeat =
                new HeartbeatMessage( m_hsId, txnId, m_safetyState.getNewestSafeTxnIdForExecutorBySiteId(initiatorId));
            try {
                m_mailbox.send( initiatorId, heartbeat);
            } catch (MessagingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void processMessage(VoltMessage message) throws Exception {
        if (m_handledFailedSites.contains(message.m_sourceHSId)) {
            m_recoveryLog.info("Dropping message " + message + " because it is from a known failed site");
        }
        if (message instanceof TransactionInfoBaseMessage) {
            TransactionInfoBaseMessage info = (TransactionInfoBaseMessage)message;

            // Special case heartbeats which only update RPQ
            if (info instanceof HeartbeatMessage) {
                // use the heartbeat to unclog the priority queue if clogged
                long lastSeenTxnFromInitiator = m_txnQueue.noteTransactionRecievedAndReturnLastSeen(
                        info.getInitiatorHSId(), info.getTxnId(),
                        true, ((HeartbeatMessage) info).getLastSafeTxnId());

                // respond to the initiator with the last seen transaction
                HeartbeatResponseMessage response = new HeartbeatResponseMessage(
                        m_hsId, lastSeenTxnFromInitiator,
                        m_txnQueue.getQueueState() == RestrictedPriorityQueue.QueueState.BLOCKED_SAFETY);
                try {
                    m_mailbox.send(info.getInitiatorHSId(), response);
                } catch (MessagingException e) {
                    // hope this never happens... it doesn't right?
                    throw new RuntimeException(e);
                }
                // we're done here (in the case of heartbeats)
                return;
            }
            assert(false);
        } else if (message instanceof HeartbeatResponseMessage) {
            HeartbeatResponseMessage hrm = (HeartbeatResponseMessage)message;
            m_safetyState.updateLastSeenTxnIdFromExecutorBySiteId(
                    hrm.getExecHSId(),
                    hrm.getLastReceivedTxnId(),
                    hrm.isBlocked());
        } else if (message instanceof LocalObjectMessage) {
            LocalObjectMessage lom = (LocalObjectMessage)message;
            if (lom.payload instanceof Runnable) {
                ((Runnable)lom.payload).run();
            } else if (lom.payload instanceof Request) {
                Request r = (Request)lom.payload;
                long txnId = 0;
                if (r.type == OpCode.createSession) {
                    txnId = r.sessionId;
                } else {
                    txnId = m_idManager.getNextUniqueTransactionId();
                }
                for (long initiatorHSId : m_hsIds) {
                    if (initiatorHSId == m_hsId) continue;
                    AgreementTaskMessage atm =
                        new AgreementTaskMessage(
                                r,
                                txnId,
                                m_hsId,
                                m_safetyState.getNewestSafeTxnIdForExecutorBySiteId(initiatorHSId));
                    try {
                        m_mailbox.send( initiatorHSId, atm);
                    } catch (MessagingException e) {
                        throw new RuntimeException(e);
                    }
                }
                //Process the ATM eagerly locally to aid
                //in having a complete set of stuff to ship
                //to a recovering agreement site
                AgreementTaskMessage atm =
                    new AgreementTaskMessage(
                            r,
                            txnId,
                            m_hsId,
                            m_safetyState.getNewestSafeTxnIdForExecutorBySiteId(m_hsId));
                processMessage(atm);
            }
        } else if (message instanceof AgreementTaskMessage) {
            AgreementTaskMessage atm = (AgreementTaskMessage)message;
            if (!m_transactionsById.containsKey(atm.m_txnId) && atm.m_txnId >= m_minTxnIdAfterRecovery) {
                m_txnQueue.noteTransactionRecievedAndReturnLastSeen(atm.m_initiatorHSId,
                        atm.m_txnId,
                        false,
                        atm.m_lastSafeTxnId);
                AgreementTransactionState transactionState =
                    new AgreementTransactionState(atm.m_txnId, atm.m_initiatorHSId, atm.m_request);
                if (m_txnQueue.add(transactionState)) {
                    m_transactionsById.put(transactionState.txnId, transactionState);
                } else {
                    m_agreementLog.info(
                            "Dropping txn " + transactionState.txnId +
                            " data from failed initiatorSiteId: " + transactionState.initiatorHSId);
                }
            } else {
                m_recoveryLog.info("Agreement, discarding duplicate txn during recovery, txnid is " + atm.m_txnId +
                        " this should only occur during recovery. minTxnIdAfterRecovery " +
                        m_minTxnIdAfterRecovery + " and  dup is " + m_transactionsById.containsKey(atm.m_txnId));
            }
        } else if (message instanceof BinaryPayloadMessage) {
            BinaryPayloadMessage bpm = (BinaryPayloadMessage)message;
            ByteBuffer metadata = ByteBuffer.wrap(bpm.m_metadata);
            final byte type = metadata.get();
            if (type == BINARY_PAYLOAD_SNAPSHOT) {
                assert(m_recovering);
                assert(m_recoveryStage == RecoveryStage.SENT_PROPOSAL);
                if (m_recoveryStage != RecoveryStage.SENT_PROPOSAL) {
                    VoltDB.crashLocalVoltDB("Received a recovery snapshot in stage " + m_recoveryStage.toString(), true, null);
                }
                long selectedRecoverBeforeTxn = metadata.getLong();
                if (selectedRecoverBeforeTxn < m_recoverBeforeTxn) {
                    VoltDB.crashLocalVoltDB("Selected recover before txn was earlier than the  proposed recover before txn", true, null);
                }
                m_recoverBeforeTxn = selectedRecoverBeforeTxn;
                m_minTxnIdAfterRecovery = m_recoverBeforeTxn;//anything before this precedes the snapshot
                try {
                    m_recoverySnapshot = org.xerial.snappy.Snappy.uncompress(bpm.m_payload);
                } catch (IOException e) {
                    VoltDB.crashLocalVoltDB("Unable to decompress ZK snapshot", true, e);
                }
                m_recoveryStage = RecoveryStage.RECEIVED_SNAPSHOT;

                /*
                 * Clean out all txns from before the snapshot
                 */
                Iterator<Map.Entry< Long, OrderableTransaction>> iter = m_transactionsById.entrySet().iterator();
                while (iter.hasNext()) {
                    final Map.Entry< Long, OrderableTransaction> entry = iter.next();
                    if (entry.getKey() < m_minTxnIdAfterRecovery) {
                        m_txnQueue.faultTransaction(entry.getValue());
                        iter.remove();
                    }
                }
            } else if (type == BINARY_PAYLOAD_JOIN_REQUEST) {
                JSONObject jsObj = new JSONObject(new String(bpm.m_payload, "UTF-8"));
                final long initiatorHSId = jsObj.getLong("initiatorHSId");
                final long txnId = jsObj.getLong("txnId");
                final long lastSafeTxnId = jsObj.getLong("lastSafeTxnId");
                final long joiningHSId = jsObj.getLong("joiningHSId");
                if (m_recovering) {
                    VoltDB.crashLocalVoltDB(
                            "Received a join request during recovery for " +
                            MiscUtils.hsIdToString(joiningHSId)  +
                            " from " + MiscUtils.hsIdToString(initiatorHSId), true, null);
                }
                m_txnQueue.noteTransactionRecievedAndReturnLastSeen(initiatorHSId,
                        txnId,
                        false,
                        lastSafeTxnId);
                AgreementRejoinTransactionState transactionState =
                    new AgreementRejoinTransactionState(txnId, initiatorHSId, joiningHSId, null);
                if (m_txnQueue.add(transactionState)) {
                    m_transactionsById.put(transactionState.txnId, transactionState);
                } else {
                    m_agreementLog.info(
                            "Dropping txn " + transactionState.txnId +
                            " data from failed initiatorSiteId: " + transactionState.initiatorHSId);
                }
            }

        } else if (message instanceof FaultMessage) {
            FaultMessage fm = (FaultMessage)message;

            if (m_pendingFailedSites.contains(fm.failedSite)) {
                m_recoveryLog.info("Received fault message for failed site " + MiscUtils.hsIdToString(fm.failedSite) +
                        " ignoring");
                return;
            }
            discoverGlobalFaultData(fm);
        } else if (message instanceof RecoveryMessage) {
            RecoveryMessage rm = (RecoveryMessage)message;
            assert(m_recoverBeforeTxn == null);
            assert(m_siteRequestingRecovery == null);
            assert(m_recovering == false);
            assert(m_recoveryStage == RecoveryStage.RECOVERED);
            m_recoverBeforeTxn = rm.txnId();
            m_siteRequestingRecovery = rm.sourceSite();
        }
    }

    private void processZKSnapshot() {
        ByteArrayInputStream bais = new ByteArrayInputStream(m_recoverySnapshot);
        try {
            DataInputStream dis = new DataInputStream(bais);
            BinaryInputArchive bia = new BinaryInputArchive(dis);
            m_server.getZKDatabase().deserializeSnapshot(bia);
            m_server.createSessionTracker();
        } catch (Exception e) {
            VoltDB.crashLocalVoltDB("Error loading agreement database", false, e);
        }
        m_recoverySnapshot = null;
        m_recoveryStage = RecoveryStage.RECOVERED;
        m_recovering = false;
        m_recoverBeforeTxn = null;
        m_recoveryComplete.countDown();
        m_agreementLog.info("Loaded ZK snapshot");
    }

    private void shipZKDatabaseSnapshot(long faultedInitiator, long txnId) throws IOException {
        m_recoveryLog.info("Shipping ZK snapshot from " + m_hsId + " to " + faultedInitiator);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        BinaryOutputArchive boa = new BinaryOutputArchive(dos);
        m_server.getZKDatabase().serializeSnapshot(boa);
        dos.flush();
        byte databaseBytes[] = org.xerial.snappy.Snappy.compress(baos.toByteArray());
        ByteBuffer metadata = ByteBuffer.allocate(9);
        metadata.put(BINARY_PAYLOAD_SNAPSHOT);
        metadata.putLong(txnId);
        BinaryPayloadMessage bpm = new BinaryPayloadMessage( metadata.array(), databaseBytes);
        try {
            m_mailbox.send( faultedInitiator, bpm);
        } catch (MessagingException e) {
            throw new IOException(e);
        }
        m_siteRequestingRecovery = null;
        m_recoverBeforeTxn = null;
    }

    private void discoverGlobalFaultData(FaultMessage faultMessage) {
        //Keep it simple and don't try to recover on the recovering node.
        if (m_recovering) {
            VoltDB.crashLocalVoltDB("Aborting recovery due to a remote node failure. Retry again.", true, null);
        }
        HashSet<Long> failedSiteIds = new HashSet<Long>();
        failedSiteIds.add(faultMessage.failedSite);
        m_pendingFailedSites.addAll(failedSiteIds);
        m_hsIds.removeAll(m_pendingFailedSites);
        HashMap<Long, Integer> expectedResponseCounts = new HashMap<Long, Integer>();
        int expectedResponses = discoverGlobalFaultData_send(expectedResponseCounts);
        HashMap<Long, Long> initiatorSafeInitPoint =
            discoverGlobalFaultData_rcv(expectedResponses, expectedResponseCounts);

        if (initiatorSafeInitPoint == null) {
            return;
        }


        // Agreed on a fault set.

        // Do the work of patching up the execution site.
        // Do a little work to identify the newly failed site ids and only handle those

        HashSet<Long> newFailedSiteIds = new HashSet<Long>(failedSiteIds);
        newFailedSiteIds.removeAll(m_handledFailedSites);

        handleSiteFaults(newFailedSiteIds, initiatorSafeInitPoint);

        m_handledFailedSites.addAll(failedSiteIds);
        m_pendingFailedSites.removeAll(newFailedSiteIds);
        assert(m_pendingFailedSites.isEmpty());
    }

    private void handleSiteFaults(HashSet<Long> newFailedSiteIds,
            HashMap<Long, Long> initiatorSafeInitPoint) {
        m_recoveryLog.info("Agreement, handling site faults for newly failed sites " +
                MiscUtils.hsIdCollectionToString(newFailedSiteIds) +
                " initiatorSafeInitPoints " + initiatorSafeInitPoint);
        // Fix safe transaction scoreboard in transaction queue
        for (Long siteId : newFailedSiteIds) {
            m_txnQueue.gotFaultForInitiator(siteId);
            m_server.closeSessions(siteId);
        }

        // Remove affected transactions from RPQ and txnId hash
        // that are not globally initiated
        Iterator<Long> it = m_transactionsById.keySet().iterator();
        while (it.hasNext())
        {
            final long tid = it.next();
            OrderableTransaction ts = m_transactionsById.get(tid);
            if (!initiatorSafeInitPoint.containsKey(ts.initiatorHSId)){
                //Not from a failed initiator, no need to inspect and potentially discard
                continue;
            }
            // Fault a transaction that was not globally initiated
            if (ts.txnId > initiatorSafeInitPoint.get(ts.initiatorHSId) &&
                    newFailedSiteIds.contains(ts.initiatorHSId))
            {
                m_recoveryLog.info("Faulting non-globally initiated transaction " + ts.txnId);
                m_txnQueue.faultTransaction(ts);
            }
        }
    }

    /**
     * Send one message to each surviving execution site providing this site's
     * multi-partition commit point and this site's safe txnid
     * (the receiver will filter the later for its
     * own partition). Do this once for each failed initiator that we know about.
     * Sends all data all the time to avoid a need for request/response.
     */
    private int discoverGlobalFaultData_send(HashMap<Long, Integer> messagesPerSite)
    {
        HashSet<Long> survivorSet = new HashSet<Long>(m_hsIds);
        survivorSet.removeAll(m_pendingFailedSites);
        long survivors[] = MiscUtils.toLongArray(survivorSet);
        m_recoveryLog.info("Agreement, Sending fault data " + m_pendingFailedSites.toString() + " to "
                + survivorSet.toString() + " survivors");
        for (Long survivor : survivors) {
            messagesPerSite.put(survivor, m_pendingFailedSites.size());
        }
        try {
            for (Long site : m_pendingFailedSites) {
                /*
                 * Check the queue for the data and get it from the ledger if necessary.\
                 * It might not even be in the ledger if the site has been failed
                 * since recovery of this node began.
                 */
                Long txnId = m_txnQueue.getNewestSafeTransactionForInitiator(site);
                if (txnId == null) {
                    txnId = m_newestSafeTransactionForInitiatorLedger.get(site);
                    //assert(txnId != null);
                } else {
                    m_newestSafeTransactionForInitiatorLedger.put(site, txnId);
                }

                FailureSiteUpdateMessage srcmsg =
                    new FailureSiteUpdateMessage(m_hsId,
                                                 m_pendingFailedSites,
                                                 site,
                                                 txnId != null ? txnId : Long.MIN_VALUE,
                                                 site);

                m_mailbox.send(survivors, srcmsg);
            }
        }
        catch (MessagingException e) {
            VoltDB.crashLocalVoltDB(e.getMessage(), false, e);
        }
        m_recoveryLog.info("Agreement, Sent fault data. Expecting " + (survivors.length * m_pendingFailedSites.size()) + " responses.");
        return (survivors.length * m_pendingFailedSites.size());
    }

    /**
     * Collect the failure site update messages from all sites This site sent
     * its own mailbox the above broadcast the maximum is local to this site.
     * This also ensures at least one response.
     *
     * Concurrent failures can be detected by additional reports from the FaultDistributor
     * or a mismatch in the set of failed hosts reported in a message from another site
     */
    private HashMap<Long, Long> discoverGlobalFaultData_rcv(
            int expectedResponses,
            HashMap<Long, Integer> expectedResponseCount)
    {
        int responses = 0;
        java.util.ArrayList<FailureSiteUpdateMessage> messages = new java.util.ArrayList<FailureSiteUpdateMessage>();
        HashMap<Long, Long> initiatorSafeInitPoint = new HashMap<Long, Long>();
        do {
            VoltMessage m = m_mailbox.recvBlocking(new Subject[] { Subject.FAILURE, Subject.FAILURE_SITE_UPDATE }, 5);
            if (m == null) {
                //Don't need to do anything here?
                continue;
            }

            FailureSiteUpdateMessage fm = null;

            if (m.getSubject() == Subject.FAILURE_SITE_UPDATE.getId()) {
                fm = (FailureSiteUpdateMessage)m;
                messages.add(fm);
            } else if (m.getSubject() == Subject.FAILURE.getId()) {
                /*
                 * If the fault distributor reports a new fault, assert that the fault currently
                 * being handled is included, redeliver the message to ourself and then abort so
                 * that the process can restart.
                 */
                Long newFault = ((FaultMessage)m).failedSite;
                m_mailbox.deliverFront(m);
                m_recoveryLog.info("Agreement, Detected a concurrent failure from FaultDistributor, new failed site "
                        + newFault);
                return null;
            }

            /*
             * If the other surviving host saw a different set of failures
             */
            if (!m_pendingFailedSites.equals(fm.m_failedHSIds)) {
                if (!m_pendingFailedSites.containsAll(fm.m_failedHSIds)) {
                    /*
                     * In this case there is a new failed host we didn't know about. Time to
                     * start the process again from square 1 with knowledge of the new failed hosts
                     * There is no need to do additional work because the execution sites
                     * will take care of it. We will pick up again when the fault distributor passes
                     * it to us. We do redeliver the message so we can pick it up again
                     * once failure discovery restarts
                     */
                    HashSet<Long> difference = new HashSet<Long>(fm.m_failedHSIds);
                    difference.removeAll(m_pendingFailedSites);
                    for (Long siteId : difference) {
                        m_messenger.reportForeignHostFailed(siteId.intValue());
                    }
                    m_recoveryLog.info("Detected a concurrent failure from " +
                            MiscUtils.hsIdToString(fm.m_sourceHSId) +
                            " with new failed sites " + MiscUtils.hsIdCollectionToString(difference));
                    m_mailbox.deliver(m);
                    return null;
                } else {
                    /*
                     * In this instance they are not equal because the message is missing some
                     * failed sites. Drop the message. The sender will detect the fault and resend
                     * the message later with the correct information.
                     */
                    HashSet<Long> difference = new HashSet<Long>(m_pendingFailedSites);
                    difference.removeAll(fm.m_failedHSIds);
                    m_recoveryLog.info("Agreement, Discarding failure message from " +
                            MiscUtils.hsIdToString(fm.m_sourceHSId) +
                            " because it was missing failed sites " + MiscUtils.hsIdCollectionToString(difference));
                    continue;
                }
            }

            expectedResponseCount.put( fm.m_sourceHSId, expectedResponseCount.get(fm.m_sourceHSId) - 1);
            ++responses;
            m_recoveryLog.info("Agreement, Received failure message " + responses + " of " + expectedResponses
                    + " from " + MiscUtils.hsIdToString(fm.m_sourceHSId) + " for failed sites " +
                    MiscUtils.hsIdCollectionToString(fm.m_failedHSIds) +
                    " safe txn id " + fm.m_safeTxnId + " failed site " + fm.m_committedTxnId);
            m_recoveryLog.info("Agreement, expecting failures messages " + expectedResponseCount);
            if (!initiatorSafeInitPoint.containsKey(fm.m_initiatorForSafeTxnId)) {
                initiatorSafeInitPoint.put(fm.m_initiatorForSafeTxnId, Long.MIN_VALUE);
            }
            initiatorSafeInitPoint.put(
                    fm.m_initiatorForSafeTxnId,
                    Math.max(initiatorSafeInitPoint.get(fm.m_initiatorForSafeTxnId), fm.m_safeTxnId));
        } while(responses < expectedResponses);
        assert(!initiatorSafeInitPoint.containsValue(Long.MIN_VALUE));
        return initiatorSafeInitPoint;
    }

    @Override
    public void request(Request r) {
        m_mailbox.deliver(new LocalObjectMessage(r));
    }

    private static class AgreementTransactionState extends OrderableTransaction {
        private final Request m_request;
        public AgreementTransactionState(long txnId, long initiatorHSId, Request r) {
            super(txnId, initiatorHSId);
            m_request = r;
        }
    }

    /*
     * Txn state associated with rejoining a node
     */
    private static final class AgreementRejoinTransactionState extends OrderableTransaction {
        private final long m_rejoiningSite;
        private final CountDownLatch m_onCompletion;
        public AgreementRejoinTransactionState(
                long txnId,
                long initiatorSiteId,
                long rejoiningSite,
                CountDownLatch onCompletion) {
            super(txnId, initiatorSiteId);
            m_rejoiningSite = rejoiningSite;
            m_onCompletion = onCompletion;
        }
    }

    @Override
    public Semaphore createSession(final ServerCnxn cnxn, final byte[] passwd, final int timeout) {
        final Semaphore sem = new Semaphore(0);
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    long sessionId = m_idManager.getNextUniqueTransactionId();
                    Random r = new Random(sessionId ^ ZooKeeperServer.superSecret);
                    r.nextBytes(passwd);
                    ByteBuffer to = ByteBuffer.allocate(4);
                    to.putInt(timeout);
                    to.flip();
                    cnxn.setSessionId(sessionId);
                    Request si = new Request(cnxn, sessionId, 0, OpCode.createSession, to, null);
                    try {
                        processMessage(new LocalObjectMessage(si));
                    } catch (Exception e) {
                        VoltDB.crashLocalVoltDB("Unexpected exception processing AgreementSite message", false, e);
                    }
                } finally {
                    sem.release();
                }
            }
        };
        m_mailbox.deliverFront(new LocalObjectMessage(r));
        return sem;
    }

    public void reportFault(long faultingSite) {
        FaultMessage fm = new FaultMessage(faultingSite);
        m_mailbox.deliver(fm);
    }

    public void waitForRecovery() throws InterruptedException {
        if (!m_recovering) {
            return;
        }
        // this timeout is totally arbitrary
        // 30s is pretty long in general, but sometimes localcluster may need this long :-(
        if (!m_recoveryComplete.await(30, TimeUnit.SECONDS)) {
            VoltDB.crashLocalVoltDB("Timed out waiting for the agreement site to recover", false, null);
        }
    }

    /*
     * Construct a ZK transaction that will add the initiator to the cluster
     */
    public CountDownLatch requestJoin(final long joiningSite) throws Exception {
        final CountDownLatch cdl = new CountDownLatch(1);
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    final long txnId = m_idManager.getNextUniqueTransactionId();
                    for (long initiatorHSId : m_hsIds) {
                        if (initiatorHSId == m_hsId) continue;
                        JSONObject jsObj = new JSONObject();
                        jsObj.put("txnId", txnId);
                        jsObj.put("initiatorHSId", m_hsId);
                        jsObj.put("joiningHSId", joiningSite);
                        jsObj.put("lastSafeTxnId", m_safetyState.getNewestSafeTxnIdForExecutorBySiteId(initiatorHSId));
                        byte payload[] = jsObj.toString(4).getBytes("UTF-8");
                        ByteBuffer metadata = ByteBuffer.allocate(1);
                        metadata.put(BINARY_PAYLOAD_JOIN_REQUEST);
                        BinaryPayloadMessage bpm = new BinaryPayloadMessage(metadata.array(), payload);
                        try {
                            m_mailbox.send( initiatorHSId, bpm);
                        } catch (MessagingException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    m_txnQueue.noteTransactionRecievedAndReturnLastSeen(m_hsId,
                            txnId,
                            false,
                            m_safetyState.getNewestSafeTxnIdForExecutorBySiteId(m_hsId));

                    AgreementRejoinTransactionState arts =
                        new AgreementRejoinTransactionState( txnId, m_hsId, joiningSite, cdl );

                    if (!m_txnQueue.add(arts)) {
                        VoltDB.crashLocalVoltDB("Shouldn't have failed to add txn", true, null);
                    }
                    m_transactionsById.put(arts.txnId, arts);
                } catch (Throwable e) {
                    VoltDB.crashLocalVoltDB("Error constructing JSON", false, e);
                }
            }
        };
        m_mailbox.deliver(new LocalObjectMessage(r));
        return cdl;
    }
}
