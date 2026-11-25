package DatabaseService;

import java.sql.*;
import java.util.Set;

/** Business snapshot CAS is distinct from the always-changing certification guard. */
public final class FinanceDataRevision {
    private FinanceDataRevision() { }
    public static boolean businessMutation(boolean mutation,String path){
        return mutation&&!Set.of("/logOff","/currencySwitch").contains(path);
    }
    static long read(Connection canonical)throws SQLException{
        try(Statement query=canonical.createStatement();ResultSet rows=query.executeQuery("SELECT DATA_REVISION FROM CLUSTER_ATOMIC_GUARD WHERE ID=1")){
            if(!rows.next())throw new SQLException("Business data revision is unavailable");
            long value=rows.getLong(1);if(rows.wasNull()||value<0||rows.next())throw new SQLException("Business data revision is invalid");return value;
        }
    }
    static String token(String migration,long value){return migration+":"+value;}
    static void require(String expected,String actual,String operation){
        if(expected==null||expected.isBlank())throw new Rejected(428,"SNAPSHOT_REQUIRED",operation);
        if(!expected.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}:(0|[1-9][0-9]{0,18})"))
            throw new Rejected(400,"SNAPSHOT_INVALID",operation);
        if(!actual.equals(expected))throw new Rejected(409,"SNAPSHOT_STALE",operation);
    }
    static long advance(Connection canonical,long expected)throws SQLException{
        if(expected==Long.MAX_VALUE)throw new SQLException("Business data revision is exhausted");
        try(PreparedStatement update=canonical.prepareStatement("UPDATE CLUSTER_ATOMIC_GUARD SET DATA_REVISION=DATA_REVISION+1 WHERE ID=1 AND DATA_REVISION=?")){
            update.setLong(1,expected);if(update.executeUpdate()!=1)throw new SQLException("Business data revision changed unexpectedly");
        }
        return expected+1;
    }
    public static final class Rejected extends RuntimeException {
        private final int status;private final String code,operation;
        Rejected(int status,String code,String operation){super("The displayed data changed or has no verified snapshot. Reload it before submitting a new request.");this.status=status;this.code=code;this.operation=operation;}
        public int httpStatus(){return status;}public String code(){return code;}public String operationId(){return operation;}
    }
}
