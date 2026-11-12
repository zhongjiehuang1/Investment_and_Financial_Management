package DatabaseService;

import Model.Tools.Passwords;
import org.json.JSONObject;
import java.sql.*;
import java.util.Locale;

/** Immutable sign-in attempt receipts and shared budgets, inside the outer guard transaction. */
final class AtomicLoginAttempts {
    static final int LOGIN_LIMIT=20,STATUS_LIMIT=20,MAX_CLIENTS=5000;
    static final long LOGIN_WINDOW=300_000L,STATUS_WINDOW=60_000L;
    @FunctionalInterface interface Clock { long now(Connection database)throws SQLException; }
    @FunctionalInterface interface Authentication { Model.User authenticate(Connection database,String email,String password)throws SQLException; }
    record Outcome(JSONObject response,Model.User user) { }
    private final Clock clock;
    private final Authentication authentication;

    AtomicLoginAttempts(){this(AtomicLoginAttempts::databaseTime,AtomicLoginAttempts::authenticateCurrent);}
    AtomicLoginAttempts(Clock clock,Authentication authentication){this.clock=clock;this.authentication=authentication;}

    static void verifySchema(Connection database)throws SQLException{
        for(String sql:new String[]{
                "SELECT CLIENT_HASH,WINDOW_START_MS,ATTEMPTS FROM LOGIN_LIMITS WHERE 1=0",
                "SELECT OPERATION_ID,CLIENT_HASH,EMAIL_HASH,REQUEST_HASH,HTTP_STATUS,USER_ID,USER_GENERATION,CREATED_AT_MS FROM LOGIN_ATTEMPTS WHERE 1=0",
                "SELECT CLIENT_HASH,WINDOW_START_MS,ATTEMPTS FROM LOGIN_STATUS_LIMITS WHERE 1=0"})
            try(Statement query=database.createStatement()){query.executeQuery(sql).close();}
    }

    Outcome login(Connection database,String operation,String client,String emailHash,String payloadHash,String email,String password)throws SQLException{
        // Receipts are immutable, and every writer already holds/certifies the
        // common guard. FOR UPDATE is unnecessary and requires an extra UPDATE
        // privilege on the current MySQL, violating SELECT/INSERT-only grants.
        try(PreparedStatement query=database.prepareStatement("SELECT EMAIL_HASH,REQUEST_HASH,HTTP_STATUS,USER_ID,USER_GENERATION FROM LOGIN_ATTEMPTS WHERE OPERATION_ID=?")){
            query.setString(1,operation);
            try(ResultSet rows=query.executeQuery()){
                if(rows.next()){
                    if(!emailHash.equals(rows.getString(1))||!payloadHash.equals(rows.getString(2)))
                        return outcome(operation,409,"UNRESOLVED",null,"This sign-in identity belongs to an earlier request. Resolve it before trying new details.");
                    int old=rows.getInt(3);
                    if(old==401||old==429)return outcome(operation,old,"COMMITTED",null,message(old));
                    if(old!=200)throw new SQLException("Unknown durable sign-in outcome");
                    int userId=rows.getInt(4);String generation=rows.getString(5);
                    // Replaying a success is not a credential/session grant.
                    // Recheck the CURRENT credential and immutable identity.
                    Model.User current=authentication.authenticate(database,email,password);
                    if(current==null||current.getId()!=userId||!generation.equalsIgnoreCase(current.getGeneration()))
                        return outcome(operation,401,"COMMITTED",null,message(401));
                    return outcome(operation,200,"COMMITTED",current,"Signed in.");
                }
            }
        }
        long now=clock.now(database);
        int status;Model.User user=null;
        if(!budget(database,"LOGIN_LIMITS",client,now,LOGIN_WINDOW,LOGIN_LIMIT))status=429;
        else {user=authentication.authenticate(database,email,password);status=user==null?401:200;}
        try(PreparedStatement insert=database.prepareStatement("INSERT INTO LOGIN_ATTEMPTS(OPERATION_ID,CLIENT_HASH,EMAIL_HASH,REQUEST_HASH,HTTP_STATUS,USER_ID,USER_GENERATION,CREATED_AT_MS) VALUES(?,?,?,?,?,?,?,?)")){
            insert.setString(1,operation);insert.setString(2,client);insert.setString(3,emailHash);insert.setString(4,payloadHash);insert.setInt(5,status);
            if(user==null){insert.setNull(6,Types.INTEGER);insert.setNull(7,Types.VARCHAR);}else{insert.setInt(6,user.getId());insert.setString(7,user.getGeneration());}
            insert.setLong(8,now);insert.executeUpdate();
        }
        return outcome(operation,status,"COMMITTED",user,message(status));
    }

    JSONObject status(Connection database,String operation,String client,String emailHash)throws SQLException{
        if(!budget(database,"LOGIN_STATUS_LIMITS",client,clock.now(database),STATUS_WINDOW,STATUS_LIMIT))
            return response(operation,429,"UNKNOWN","Too many result checks. Wait before checking again.");
        try(PreparedStatement query=database.prepareStatement("SELECT EMAIL_HASH,HTTP_STATUS FROM LOGIN_ATTEMPTS WHERE OPERATION_ID=?")){
            query.setString(1,operation);
            try(ResultSet rows=query.executeQuery()){
                if(!rows.next())return response(operation,200,"ABSENT","No committed sign-in attempt.");
                if(!emailHash.equals(rows.getString(1)))return response(operation,409,"UNKNOWN","The previous sign-in result cannot be verified with these details.");
                return response(operation,200,"COMMITTED","Previous sign-in attempt confirmed.").put("loginHttpStatus",rows.getInt(2));
            }
        }
    }

    private static Outcome outcome(String operation,int code,String state,Model.User user,String message){
        return new Outcome(response(operation,code,state,message),user);
    }
    private static JSONObject response(String operation,int code,String state,String message){
        return new JSONObject().put("status",code==200?"success":"failure").put("httpStatus",code)
                .put("operationId",operation).put("operationState",state).put("message",message);
    }
    private static String message(int code){return code==200?"Signed in.":code==429?"Too many sign-in attempts. Try again in five minutes.":"Invalid email or password.";}

    /** Caller holds the certification guard; this is not a cross-member row-lock claim. */
    private static boolean budget(Connection database,String table,String client,long now,long window,int limit)throws SQLException{
        if(now<0)throw new SQLException("Database time is unavailable");
        try(PreparedStatement prune=database.prepareStatement("DELETE FROM "+table+" WHERE WINDOW_START_MS<=?")){
            prune.setLong(1,now-window);prune.executeUpdate();
        }
        try(PreparedStatement query=database.prepareStatement("SELECT WINDOW_START_MS,ATTEMPTS FROM "+table+" WHERE CLIENT_HASH=? FOR UPDATE")){
            query.setString(1,client);
            try(ResultSet rows=query.executeQuery()){
                if(rows.next()){
                    long start=rows.getLong(1);int attempts=rows.getInt(2);
                    if(start>now||attempts<0||attempts>=limit)return false;
                    try(PreparedStatement update=database.prepareStatement("UPDATE "+table+" SET ATTEMPTS=ATTEMPTS+1 WHERE CLIENT_HASH=?")){
                        update.setString(1,client);if(update.executeUpdate()!=1)throw new SQLException("Sign-in budget changed unexpectedly");
                    }
                    return true;
                }
            }
        }
        try(Statement query=database.createStatement();ResultSet rows=query.executeQuery("SELECT COUNT(*) FROM "+table)){
            if(!rows.next()||rows.getLong(1)>=MAX_CLIENTS)return false;
        }
        try(PreparedStatement insert=database.prepareStatement("INSERT INTO "+table+"(CLIENT_HASH,WINDOW_START_MS,ATTEMPTS) VALUES(?,?,1)")){
            insert.setString(1,client);insert.setLong(2,now);insert.executeUpdate();
        }
        return true;
    }

    private static long databaseTime(Connection database)throws SQLException{
        try(Statement query=database.createStatement();ResultSet rows=query.executeQuery("SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000")){
            if(!rows.next())throw new SQLException("Database time is unavailable");
            try{return rows.getBigDecimal(1).longValueExact();}catch(ArithmeticException|NullPointerException invalid){throw new SQLException("Invalid database time");}
        }
    }

    static Model.User authenticateCurrent(Connection database,String email,String password)throws SQLException{
        String normalized=email.trim().toLowerCase(Locale.ROOT);
        int id;String generation,stored,forename,surname;
        try(PreparedStatement query=database.prepareStatement("SELECT ID,GENERATION,PASSWORD,FORENAME,SURNAME FROM USERS WHERE LOWER(EMAIL_ADDRESS)=? LIMIT 2 FOR UPDATE")){
            query.setString(1,normalized);
            try(ResultSet rows=query.executeQuery()){
                if(!rows.next()){Passwords.verify(password,null);return null;}
                id=rows.getInt(1);generation=rows.getString(2);stored=rows.getString(3);forename=rows.getString(4);surname=rows.getString(5);
                if(rows.next()){Passwords.verify(password,null);return null;}
            }
        }
        if(!Passwords.verify(password,stored))return null;
        if(!RequestIdentity.validGeneration(generation))throw new SQLException("Invalid credential identity");
        if(!Passwords.isEncoded(stored))try(PreparedStatement upgrade=database.prepareStatement("UPDATE USERS SET PASSWORD=? WHERE ID=? AND GENERATION=? AND PASSWORD=?")){
            upgrade.setString(1,Passwords.encode(password));upgrade.setInt(2,id);upgrade.setString(3,generation);upgrade.setString(4,stored);
            if(upgrade.executeUpdate()!=1)throw new SQLException("Credential changed during sign-in");
        }
        Model.User user=new Model.User();user.setId(id);user.setGeneration(generation);user.setForename(forename);user.setSurname(surname);user.setEmailAddress(normalized);
        return user;
    }
}
