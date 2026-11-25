package DatabaseService;

import com.arjuna.ats.arjuna.common.arjPropertyManager;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.internal.jta.recovery.arjunacore.XARecoveryModule;
import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple;
import com.arjuna.ats.jta.common.jtaPropertyManager;
import com.arjuna.ats.jta.recovery.XAResourceRecoveryHelper;
import jakarta.transaction.TransactionManager;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.*;

/** Narayana owns every XA decision and durable recovery record. No last-resource optimization is used. */
final class XaTransactions implements AutoCloseable {
    interface Journal {
        void uid(String operation,String uid) throws Exception;
        void started(String operation,int node,Xid xid) throws Exception;
        void ended(int node,Xid xid,String terminal) throws Exception;
        boolean committed(int node,Xid xid) throws Exception;
    }
    interface Work { void run(Map<Integer,Connection> connections) throws Exception; }
    interface ConnectionGuard { void verify(Connection connection) throws Exception; }
    enum Outcome { COMMITTED, ABORTED, UNKNOWN }
    private final Map<Integer,XADataSource> sources;
    private final Journal journal;
    private final TransactionManager manager;
    private final RecoveryManager recovery;
    private final List<Helper> helpers=new ArrayList<>();
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final String resourceNamespace;
    private final ConnectionGuard connectionGuard;

    XaTransactions(Map<Integer,XADataSource> sources,Journal journal,Path store,String identifier) throws Exception {
        this(sources,journal,store,identifier,null);
    }
    XaTransactions(Map<Integer,XADataSource> sources,Journal journal,Path store,String identifier,javax.sql.DataSource decisionStore) throws Exception {
        this(sources,journal,store,identifier,decisionStore,connection->{});
    }
    XaTransactions(Map<Integer,XADataSource> sources,Journal journal,Path store,String identifier,javax.sql.DataSource decisionStore,ConnectionGuard guard) throws Exception {
        this.sources=sources;this.journal=journal;this.resourceNamespace=identifier;
        this.connectionGuard=Objects.requireNonNull(guard);
        if(!store.isAbsolute()||!identifier.matches("[A-Za-z0-9_-]{1,28}"))throw new IllegalArgumentException("Invalid transaction store configuration.");
        Files.createDirectories(store);
        lockChannel=FileChannel.open(store.resolve("coordinator.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        processLock=lockChannel.tryLock();
        if(processLock==null){lockChannel.close();throw new IllegalStateException("Another coordinator owns this transaction store.");}
        arjPropertyManager.getCoreEnvironmentBean().setNodeIdentifier(identifier);
        arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreDir(store.toString());
        try {
            if(decisionStore!=null){JdbcDecisionStore.requireEmptyLegacyStore(store);JdbcDecisionStore.configure(decisionStore,identifier,store);}
            boolean jdbcActive=com.arjuna.ats.arjuna.objectstore.StoreManager.getRecoveryStore() instanceof com.arjuna.ats.internal.arjuna.objectstore.jdbc.JDBCStore;
            if(jdbcActive!=(decisionStore!=null))throw new IllegalStateException("Transaction store already initialized with a different backend; restart the application");
        }catch(java.io.IOException|RuntimeException|Error failure){processLock.release();lockChannel.close();throw failure;}
        arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreSync(true);
        arjPropertyManager.getObjectStoreEnvironmentBean().setTransactionSync(true);
        arjPropertyManager.getCoordinatorEnvironmentBean().setCommitOnePhase(false);
        arjPropertyManager.getCoordinatorEnvironmentBean().setDynamic1PC(false);
        arjPropertyManager.getCoordinatorEnvironmentBean().setAsyncCommit(false);
        arjPropertyManager.getCoordinatorEnvironmentBean().setMaintainHeuristics(true);
        arjPropertyManager.getCoordinatorEnvironmentBean().setTransactionStatusManagerEnable(false);
        recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryListener(false);
        recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryBackoffPeriod(1);
        recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryModuleClassNames(List.of(
                "com.arjuna.ats.internal.arjuna.recovery.AtomicActionRecoveryModule",
                "com.arjuna.ats.internal.jta.recovery.arjunacore.XARecoveryModule"));
        jtaPropertyManager.getJTAEnvironmentBean().setXaRecoveryNodes(List.of(identifier));
        jtaPropertyManager.getJTAEnvironmentBean().setXAResourceRecordWrappingPlugin(new com.arjuna.ats.internal.jta.resources.arjunacore.XAResourceRecordWrappingPlugin(){
            public void transcribeWrapperData(com.arjuna.ats.internal.jta.resources.arjunacore.XAResourceRecord record){
                if(record.value() instanceof org.jboss.tm.XAResourceWrapper){org.jboss.tm.XAResourceWrapper resource=(org.jboss.tm.XAResourceWrapper)record.value();record.setJndiName(resource.getJndiName());record.setProductName(resource.getProductName());record.setProductVersion(resource.getProductVersion());}
            }
            public Integer getEISName(XAResource resource){return resource instanceof Tracked?((Tracked)resource).node:0;}
            public String getEISName(Integer node){return resourceNamespace+"/node/"+node;}
        });
        jtaPropertyManager.getJTAEnvironmentBean().setXaResourceOrphanFilterClassNames(List.of(
                "com.arjuna.ats.internal.jta.recovery.arjunacore.JTATransactionLogXAResourceOrphanFilter",
                "com.arjuna.ats.internal.jta.recovery.arjunacore.NodeNameXAResourceOrphanFilter"));
        manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
        recovery=RecoveryManager.manager(RecoveryManager.DIRECT_MANAGEMENT);
        recovery.initialize();
        XARecoveryModule module=XARecoveryModule.getRegisteredXARecoveryModule();
        if(module==null)throw new IllegalStateException("XA recovery module unavailable.");
        for(int node:sources.keySet()){Helper helper=new Helper(node);helpers.add(helper);module.addXAResourceRecoveryHelper(helper);}
    }

    synchronized void recover() { recovery.scan(); }

    Outcome execute(String operation,Set<Integer> participants,Work work) {
        List<XAConnection> opened=new ArrayList<>();
        boolean committing=false;
        try {
            manager.setTransactionTimeout(60);manager.begin();
            var transaction=manager.getTransaction();
            journal.uid(operation,((TransactionImple)transaction).get_uid().stringForm());
            Map<Integer,Connection> connections=new TreeMap<>();
            for(int node:new TreeSet<>(participants)) {
                XAConnection xa=sources.get(node).getXAConnection();opened.add(xa);
                Connection connection=xa.getConnection();connectionGuard.verify(connection);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                if(!transaction.enlistResource(new Tracked(xa.getXAResource(),node,operation)))throw new IllegalStateException("Resource enlistment failed.");
                connections.put(node,connection);
            }
            work.run(connections);committing=true;manager.commit();return Outcome.COMMITTED;
        } catch(jakarta.transaction.RollbackException rolledBack) { return Outcome.ABORTED; }
        catch(Exception failure) {
            if(!committing)try{if(manager.getTransaction()!=null){manager.rollback();return Outcome.ABORTED;}}catch(Exception uncertain){/* Recovery retains the gate. */}
            return Outcome.UNKNOWN;
        } finally {
            try { if(manager.getTransaction()!=null) manager.suspend(); } catch(Exception ignored) { }
            for(XAConnection connection:opened)try{connection.close();}catch(Exception ignored){ }
        }
    }

    boolean hasDecisionLog(String uid) throws Exception {
        if(uid==null)return false;
        return com.arjuna.ats.arjuna.objectstore.StoreManager.getRecoveryStore().currentState(
                new com.arjuna.ats.arjuna.common.Uid(uid),"/StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction")
                !=com.arjuna.ats.arjuna.objectstore.StateStatus.OS_UNKNOWN;
    }

    boolean isLive(String uid) {
        return uid!=null&&TransactionImple.getTransaction(new com.arjuna.ats.arjuna.common.Uid(uid))!=null;
    }

    boolean hasUnresolvedDecisionLogs()throws Exception {
        var states=new com.arjuna.ats.arjuna.state.InputObjectState();
        if(!com.arjuna.ats.arjuna.objectstore.StoreManager.getRecoveryStore().allObjUids(
                "/StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction",states))throw new IllegalStateException("Cannot enumerate recovery decisions.");
        while(true){
            var uid=com.arjuna.ats.internal.arjuna.common.UidHelper.unpackFrom(states);
            if(uid.equals(com.arjuna.ats.arjuna.common.Uid.nullUid()))return false;
            if(!isLive(uid.stringForm()))return true;
        }
    }
    boolean hasOutstandingDecisions()throws Exception{return hasUnresolvedDecisionLogs();}

    /** Presumed abort is allowed only after the caller establishes no live transaction and no decision log. */
    void abortOrphan(int node,Xid xid)throws Exception{
        XAConnection connection=sources.get(node).getXAConnection();
        try{connectionGuard.verify(connection.getConnection());new Tracked(connection.getXAResource(),node,null).rollback(xid);}finally{connection.close();}
    }

    private final class Tracked implements org.jboss.tm.XAResourceWrapper {
        final XAResource delegate;final int node;final String operation;
        Tracked(XAResource delegate,int node,String operation){this.delegate=delegate;this.node=node;this.operation=operation;}
        public XAResource getResource(){return delegate;}
        public String getProductName(){return "JDBC";}
        public String getProductVersion(){return "1";}
        public String getJndiName(){return resourceNamespace+"/node/"+node;}
        public void start(Xid xid,int flags)throws XAException{
            if(operation!=null&&flags==TMNOFLAGS)try{journal.started(operation,node,xid);}catch(Exception failure){throw xaFailure();}
            delegate.start(xid,flags);
        }
        public void end(Xid xid,int flags)throws XAException{delegate.end(xid,flags);}
        public int prepare(Xid xid)throws XAException{return delegate.prepare(xid);}
        public void commit(Xid xid,boolean onePhase)throws XAException {
            try{delegate.commit(xid,onePhase);}
            catch(XAException failure){
                if(failure.errorCode!=XAException.XAER_NOTA)throw failure;
                try{if(!journal.committed(node,xid))throw failure;}catch(XAException original){throw original;}catch(Exception unavailable){throw xaFailure();}
            }
            // A journal failure after a confirmed commit must retain the
            // decision for retry rather than report a heuristic rollback.
            try{journal.ended(node,xid,"COMMITTED");}catch(Exception failure){throw journalRetry(failure);}
        }
        public void rollback(Xid xid)throws XAException {
            try{delegate.rollback(xid);}
            catch(XAException failure){
                if(failure.errorCode!=XAException.XAER_NOTA)throw failure;
                try{if(journal.committed(node,xid))throw failure;}catch(XAException original){throw original;}catch(Exception unavailable){throw xaFailure();}
            }
            try{journal.ended(node,xid,"ABORTED");}catch(Exception failure){throw xaFailure();}
        }
        public void forget(Xid xid)throws XAException{delegate.forget(xid);}
        public Xid[] recover(int flag)throws XAException{return delegate.recover(flag);}
        public boolean isSameRM(XAResource other)throws XAException{
            // Loopback tunnels can share a hostname while reaching different MySQL
            // servers. Driver URL heuristics must never merge distinct logical nodes.
            return other instanceof Tracked&&node==((Tracked)other).node&&delegate.isSameRM(((Tracked)other).delegate);
        }
        public int getTransactionTimeout()throws XAException{return delegate.getTransactionTimeout();}
        public boolean setTransactionTimeout(int seconds)throws XAException{return delegate.setTransactionTimeout(seconds);}
    }
    private static XAException xaFailure(){return new XAException(XAException.XAER_RMERR);}
    private static XAException journalRetry(Exception cause){
        XAException failure=new XAException(XAException.XA_RETRY);
        if(cause instanceof java.sql.SQLException sql)failure.initCause(new java.sql.SQLException("Transaction journal update failed",sql.getSQLState(),sql.getErrorCode()));
        else failure.initCause(new IllegalStateException("Transaction journal update failed: "+cause.getClass().getSimpleName()));
        return failure;
    }

    private final class Helper implements XAResourceRecoveryHelper {
        final int node;XAConnection connection;Connection logical;XAResource resource;
        Helper(int node){this.node=node;}
        public boolean initialise(String ignored){return true;}
        public synchronized XAResource[] getXAResources()throws Exception{
            boolean valid=false;
            if(logical!=null)try{valid=logical.isValid(2);}catch(Exception ignored){ }
            if(!valid){
                close();connection=sources.get(node).getXAConnection();logical=connection.getConnection();
                resource=new Tracked(connection.getXAResource(),node,null);
            }
            connectionGuard.verify(logical);
            return new XAResource[]{resource};
        }
        synchronized void close(){if(connection!=null)try{connection.close();}catch(Exception ignored){ }finally{connection=null;logical=null;resource=null;}}
    }

    /** Only used after the application has drained all executing operations. */
    public void close()throws Exception{
        recovery.terminate(false);for(Helper helper:helpers)helper.close();processLock.release();lockChannel.close();
    }
}
