/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import org.apache.zookeeper_voltpatches.KeeperException;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.HostMessenger;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.Subject;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.utils.CoreUtils;
import org.voltdb.RealVoltDB;
import org.voltdb.VoltDB;
import org.voltdb.VoltZK;
import org.voltdb.exceptions.TransactionRestartException;
import org.voltdb.messaging.CompleteTransactionMessage;
import org.voltdb.messaging.DummyTransactionTaskMessage;
import org.voltdb.messaging.DumpMessage;
import org.voltdb.messaging.FragmentResponseMessage;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.InitiateResponseMessage;
import org.voltdb.messaging.Iv2InitiateTaskMessage;
import org.voltdb.messaging.Iv2RepairLogRequestMessage;
import org.voltdb.messaging.Iv2RepairLogResponseMessage;
import org.voltdb.messaging.RejoinMessage;
import org.voltdb.messaging.RepairLogTruncationMessage;
import com.google_voltpatches.common.base.Supplier;

/**
 * InitiatorMailbox accepts initiator work and proxies it to the
 * configured InitiationRole.
 *
 * If you add public synchronized methods that will be used on the MpInitiator then
 * you need to override them in MpInitiator mailbox so that they
 * occur in the correct thread instead of using synchronization
 */
public class InitiatorMailbox implements Mailbox
{
    static final boolean LOG_TX = false;
    public static final boolean SCHEDULE_IN_SITE_THREAD;
    static {
        SCHEDULE_IN_SITE_THREAD = Boolean.valueOf(System.getProperty("SCHEDULE_IN_SITE_THREAD", "true"));
    }

    VoltLogger hostLog = new VoltLogger("HOST");
    VoltLogger tmLog = new VoltLogger("TM");

    protected final int m_partitionId;
    protected final Scheduler m_scheduler;
    protected final HostMessenger m_messenger;
    protected final RepairLog m_repairLog;
    private final JoinProducerBase m_joinProducer;
    private final LeaderCacheReader m_masterLeaderCache;
    private long m_hsId;
    protected RepairAlgo m_algo;

    /*
     * Hacky global map of initiator mailboxes to support assertions
     * that verify the locking is kosher
     */
    public static final CopyOnWriteArrayList<InitiatorMailbox> m_allInitiatorMailboxes
                                                                         = new CopyOnWriteArrayList<InitiatorMailbox>();

    synchronized public void setLeaderState(long maxSeenTxnId)
    {
        setLeaderStateInternal(maxSeenTxnId);
    }

    public synchronized void setMaxLastSeenMultipartTxnId(long txnId) {
        setMaxLastSeenMultipartTxnIdInternal(txnId);
    }


    synchronized public void setMaxLastSeenTxnId(long txnId) {
        setMaxLastSeenTxnIdInternal(txnId);
    }

    synchronized public void enableWritingIv2FaultLog() {
        enableWritingIv2FaultLogInternal();
    }

    synchronized public RepairAlgo constructRepairAlgo(Supplier<List<Long>> survivors, String whoami, boolean isBalanceSPI) {
        RepairAlgo ra = new SpPromoteAlgo( survivors.get(), this, whoami, m_partitionId, isBalanceSPI);
        if (hostLog.isDebugEnabled()) {
            hostLog.debug("[InitiatorMailbox:constructRepairAlgo] whoami: " + whoami + ", partitionId: " +
                    m_partitionId + ", survivors: " + Arrays.toString(survivors.get().toArray()));
        }
        setRepairAlgoInternal(ra);
        return ra;
    }

    synchronized public RepairAlgo constructRepairAlgo(Supplier<List<Long>> survivors, String whoami) {
        return constructRepairAlgo(survivors, whoami, false);
    }
    protected void setRepairAlgoInternal(RepairAlgo algo)
    {
        assert(lockingVows());
        m_algo = algo;
    }

    protected void setLeaderStateInternal(long maxSeenTxnId)
    {
        assert(lockingVows());
        m_repairLog.setLeaderState(true);
        m_scheduler.setLeaderState(true);
        m_scheduler.setMaxSeenTxnId(maxSeenTxnId);

        // After SP leader promotion, a DummyTransactionTaskMessage is generated from the new leader.
        // This READ ONLY message will serve as a synchronization point on all replicas of this
        // partition, like normal SP write transaction that has to finish executing on all replicas.
        // In this way, the leader can make sure all replicas have finished replaying
        // all their repair logs entries.
        // From now on, the new leader is safe to accept new transactions. See ENG-11110.
        // Deliver here is to make sure it's the first message on the new leader.
        // On MP scheduler, this DummyTransactionTaskMessage will be ignored.
        deliver(new DummyTransactionTaskMessage());
    }

    protected void setMaxLastSeenMultipartTxnIdInternal(long txnId) {
        assert(lockingVows());
        m_repairLog.m_lastMpHandle = txnId;
    }


    protected void setMaxLastSeenTxnIdInternal(long txnId) {
        assert(lockingVows());
        m_scheduler.setMaxSeenTxnId(txnId);
    }

    protected void enableWritingIv2FaultLogInternal() {
        assert(lockingVows());
        m_scheduler.enableWritingIv2FaultLog();
    }

    public InitiatorMailbox(int partitionId,
            Scheduler scheduler,
            HostMessenger messenger, RepairLog repairLog,
            JoinProducerBase joinProducer)
    {
        m_partitionId = partitionId;
        m_scheduler = scheduler;
        m_messenger = messenger;
        m_repairLog = repairLog;
        m_joinProducer = joinProducer;

        m_masterLeaderCache = new LeaderCache(m_messenger.getZK(), VoltZK.iv2masters);
        try {
            m_masterLeaderCache.start(false);
        } catch (InterruptedException ignored) {
            // not blocking. shouldn't interrupt.
        } catch (ExecutionException crashme) {
            // this on the other hand seems tragic.
            VoltDB.crashLocalVoltDB("Error constructiong InitiatorMailbox.", false, crashme);
        }

        /*
         * Leaking this from a constructor, real classy.
         * Only used for an assertion on locking.
         */
        m_allInitiatorMailboxes.add(this);
    }

    public JoinProducerBase getJoinProducer()
    {
        return m_joinProducer;
    }

    // enforce restriction on not allowing promotion during rejoin.
    public boolean acceptPromotion()
    {
        return m_joinProducer == null || m_joinProducer.acceptPromotion();
    }

    /*
     * Thou shalt not lock two initiator mailboxes from the same thread, lest ye be deadlocked.
     */
    public static boolean lockingVows() {
        List<InitiatorMailbox> lockedMailboxes = new ArrayList<InitiatorMailbox>();
        for (InitiatorMailbox im : m_allInitiatorMailboxes) {
            if (Thread.holdsLock(im)) {
                lockedMailboxes.add(im);
            }
        }
        if (lockedMailboxes.size() > 1) {
            String msg = "Unexpected concurrency error, a thread locked two initiator mailboxes. ";
            msg += "Mailboxes for site id/partition ids ";
            boolean first = true;
            for (InitiatorMailbox m : lockedMailboxes) {
                msg += CoreUtils.hsIdToString(m.m_hsId) + "/" + m.m_partitionId;
                if (!first) {
                    msg += ", ";
                }
                first = false;
            }
            VoltDB.crashLocalVoltDB(msg, true, null);
        }
        return true;
    }

    synchronized public void shutdown() throws InterruptedException
    {
        shutdownInternal();
    }

    protected void shutdownInternal() throws InterruptedException {
        assert(lockingVows());
        m_masterLeaderCache.shutdown();
        if (m_algo != null) {
            m_algo.cancel();
        }
        m_scheduler.shutdown();
    }

    // Change the replica set configuration (during or after promotion)
    public synchronized void updateReplicas(List<Long> replicas, Map<Integer, Long> partitionMasters)
    {
        updateReplicasInternal(replicas, partitionMasters);
    }

    protected void updateReplicasInternal(List<Long> replicas, Map<Integer, Long> partitionMasters) {
        assert(lockingVows());
        Iv2Trace.logTopology(getHSId(), replicas, m_partitionId);
        // If a replica set has been configured and it changed during
        // promotion, must cancel the term
        if (m_algo != null) {
            m_algo.cancel();
        }
        m_scheduler.updateReplicas(replicas, partitionMasters);
    }

    public long getMasterHsId(int partitionId)
    {
        long masterHSId = m_masterLeaderCache.get(partitionId);
        return masterHSId;
    }

    @Override
    public void send(long destHSId, VoltMessage message)
    {
        logTxMessage(message);
        message.m_sourceHSId = this.m_hsId;
        m_messenger.send(destHSId, message);
    }

    @Override
    public void send(long[] destHSIds, VoltMessage message)
    {
        logTxMessage(message);
        message.m_sourceHSId = this.m_hsId;
        m_messenger.send(destHSIds, message);
    }

    @Override
    public void deliver(final VoltMessage message)
    {
        if (SCHEDULE_IN_SITE_THREAD) {
            this.m_scheduler.getQueue().offer(new SiteTasker.SiteTaskerRunnable() {
                @Override
                void run() {
                    synchronized (InitiatorMailbox.this) {
                        deliverInternal(message);
                    }
                }
            });
        } else {
            synchronized (this) {
                deliverInternal(message);
            }
        }
    }

    protected void deliverInternal(VoltMessage message) {
        assert(lockingVows());
        logRxMessage(message);
        boolean canDeliver = m_scheduler.sequenceForReplay(message);
        if (message instanceof DumpMessage) {
            hostLog.warn("Received DumpMessage at " + CoreUtils.hsIdToString(m_hsId));
            try {
                m_scheduler.dump();
            } catch (Throwable ignore) {
                hostLog.warn("Failed to dump the content of the scheduler", ignore);
            }
        }
        if (message instanceof Iv2RepairLogRequestMessage) {
            handleLogRequest(message);
            return;
        }
        else if (message instanceof Iv2RepairLogResponseMessage) {
            m_algo.deliver(message);
            return;
        }
        else if (message instanceof RejoinMessage) {
            m_joinProducer.deliver((RejoinMessage) message);
            return;
        }
        else if (message instanceof RepairLogTruncationMessage) {
            m_repairLog.deliver(message);
            return;
        }
        else if (message instanceof Iv2InitiateTaskMessage) {
            if (checkMisroutedIv2IntiateTaskMessage((Iv2InitiateTaskMessage)message)) {
                return;
            }
            handleSPIBalanceIfRequested((Iv2InitiateTaskMessage)message);
        } else if (message instanceof FragmentTaskMessage) {
            if (checkMisroutedFragmentTaskMessage((FragmentTaskMessage)message)) {
                return;
            }
        }

        m_repairLog.deliver(message);
        if (canDeliver) {
            m_scheduler.deliver(message);
        }
    }

    private void handleSPIBalanceIfRequested(Iv2InitiateTaskMessage msg) {

        if (!"@BalanceSPI".equals(msg.getStoredProcedureName())) {
            return;
        }

        final Object[] params = msg.getParameters();
        int pid = Integer.parseInt(params[1].toString());
        if (pid != m_partitionId) {
            tmLog.warn(String.format("@BalanceSPI executed at a wrong partition %d for partition %d.", m_partitionId, pid));
            return;
        }

        RealVoltDB db = (RealVoltDB)VoltDB.instance();
        int hostId = Integer.parseInt(params[2].toString());
        Long newLeaderHSId = db.getCartograhper().getHSIDForPartitionHost(hostId, pid);
        if (newLeaderHSId == m_hsId) {
            tmLog.warn(String.format("@BalanceSPI the partition leader is already on the host %d.", hostId));
            return;
        }

        startSPIMigration(pid, newLeaderHSId);
        if (tmLog.isDebugEnabled()) {
            tmLog.debug(VoltZK.debugLeadersInfo(m_messenger.getZK()));
        }
    }

    // if the SPI migration has been requested, the site will be immediately treated as non-leader
    // all the requests will be sent back to the sender if these requests are intended for leader
    // These transactions will be restarted.
    private boolean checkMisroutedIv2IntiateTaskMessage(Iv2InitiateTaskMessage message) {
        if (m_scheduler.isLeader() || message.toReplica()) {
            return false;
        }
        InitiateResponseMessage response = new InitiateResponseMessage(message);
        response.setMisrouted(message.getStoredProcedureInvocation());
        response.m_sourceHSId = getHSId();
        Iv2Trace.logMisroutedTransaction(message, getHSId());
        deliver(response);
        return true;
    }

    // if SPI migration has been requested, the site will be immediately treated as non-leader
    // all the requests will be sent back to the sender if these requests are intended for leader
    // These transactions will be restarted.
    private boolean checkMisroutedFragmentTaskMessage(FragmentTaskMessage message) {
        if (m_scheduler.isLeader() || message.toReplica() || message.getCurrentBatchIndex() > 0) {
            return false;
        }
        FragmentResponseMessage response = new FragmentResponseMessage(message, getHSId());
        TransactionRestartException restart = new TransactionRestartException(
                "Transaction being restarted due to SPI migration.", message.getTxnId());
        response.setStatus(FragmentResponseMessage.UNEXPECTED_ERROR, restart);
        response.m_sourceHSId = getHSId();
        Iv2Trace.logMisroutedFragmentTaskMessage(message, getHSId());
        deliver(response);
        return true;
    }

    // start the process of appointing a new leader for the partition
    public void startSPIMigration(int partition, long newHSID) {
        LeaderCache leaderAppointee = new LeaderCache(m_messenger.getZK(), VoltZK.iv2appointees);
        try {
            leaderAppointee.start(true);
            leaderAppointee.put(partition, VoltZK.suffixHSIdsWithBalanceSPIRequest(newHSID));
        } catch (InterruptedException | ExecutionException | KeeperException e) {
            VoltDB.crashLocalVoltDB("fail to start SPI migration",true, e);
        } finally {
            try {
                leaderAppointee.shutdown();
            } catch (InterruptedException e) {
            }
        }
        m_scheduler.m_isLeader = false;
        m_repairLog.setLeaderState(false);
        tmLog.info("[InitiatorMailbox] starting Balance SPI for partition " + partition + " to " +
                CoreUtils.hsIdToString(newHSID) + ". isLeader:" + m_scheduler.m_isLeader);
    }

    @Override
    public VoltMessage recv()
    {
        return null;
    }

    @Override
    public void deliverFront(VoltMessage message)
    {
        throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public VoltMessage recvBlocking()
    {
        throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public VoltMessage recvBlocking(long timeout)
    {
        throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public VoltMessage recv(Subject[] s)
    {
        throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public VoltMessage recvBlocking(Subject[] s)
    {
        throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public VoltMessage recvBlocking(Subject[] s, long timeout)
    {
        throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public long getHSId()
    {
        return m_hsId;
    }

    @Override
    public void setHSId(long hsId)
    {
        this.m_hsId = hsId;
    }

    /** Produce the repair log. This is idempotent. */
    private void handleLogRequest(VoltMessage message)
    {
        Iv2RepairLogRequestMessage req = (Iv2RepairLogRequestMessage)message;
        List<Iv2RepairLogResponseMessage> logs = m_repairLog.contents(req.getRequestId(),
                req.isMPIRequest());

        if (tmLog.isDebugEnabled()) {
            tmLog.debug(CoreUtils.hsIdToString(getHSId())
                    + " handling repair log request id " + req.getRequestId()
                    + " for " + CoreUtils.hsIdToString(message.m_sourceHSId));
        }
        for (Iv2RepairLogResponseMessage log : logs) {
            send(message.m_sourceHSId, log);
        }
    }

    /**
     * Create a real repair message from the msg repair log contents and
     * instruct the message handler to execute a repair. Single partition
     * work needs to do duplicate counting; MPI can simply broadcast the
     * repair to the needs repair units -- where the SP will do the rest.
     */
    void repairReplicasWith(List<Long> needsRepair, VoltMessage repairWork)
    {
        //For an SpInitiator the lock should already have been acquire since
        //this method is reach via SpPromoteAlgo.deliver which is reached by InitiatorMailbox.deliver
        //which should already have acquire the lock
        assert(Thread.holdsLock(this));
        repairReplicasWithInternal(needsRepair, repairWork);
    }

    private void repairReplicasWithInternal(List<Long> needsRepair, VoltMessage repairWork) {
        assert(lockingVows());
        if (repairWork instanceof Iv2InitiateTaskMessage) {
            Iv2InitiateTaskMessage m = (Iv2InitiateTaskMessage)repairWork;
            Iv2InitiateTaskMessage work = new Iv2InitiateTaskMessage(m.getInitiatorHSId(), getHSId(), m);
            m_scheduler.handleMessageRepair(needsRepair, work);
        }
        else if (repairWork instanceof FragmentTaskMessage) {
            // We need to get this into the repair log in case we've never seen it before.  Adding fragment
            // tasks to the repair log is safe; we'll never overwrite the first fragment if we've already seen it.
            m_repairLog.deliver(repairWork);
            m_scheduler.handleMessageRepair(needsRepair, repairWork);
        }
        else if (repairWork instanceof CompleteTransactionMessage) {
            // CompleteTransactionMessages should always be safe to handle.  Either the work was done, and we'll
            // ignore it, or we need to clean up, or we'll be restarting and it doesn't matter.  Make sure they
            // get into the repair log and then let them run their course.
            m_repairLog.deliver(repairWork);
            m_scheduler.handleMessageRepair(needsRepair, repairWork);
        }
        else {
            throw new RuntimeException("Invalid repair message type: " + repairWork);
        }
    }

    private void logRxMessage(VoltMessage message)
    {
        Iv2Trace.logInitiatorRxMsg(message, m_hsId);
    }

    private void logTxMessage(VoltMessage message)
    {
        if (LOG_TX) {
            hostLog.info("TX HSID: " + CoreUtils.hsIdToString(m_hsId) +
                    ": " + message);
        }
    }

    public void notifyOfSnapshotNonce(String nonce, long snapshotSpHandle) {
        if (m_joinProducer == null) return;
        m_joinProducer.notifyOfSnapshotNonce(nonce, snapshotSpHandle);
    }
}
