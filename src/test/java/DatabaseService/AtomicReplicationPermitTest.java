package DatabaseService;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AtomicReplicationPermitTest {
    private static final String UUID="11111111-1111-1111-1111-111111111111",TOKEN="synthetic-authority-token-not-production";
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final Map<String,String> headers=new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String,String> confirmationHeaders=new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicReference<JSONObject> confirmed=new AtomicReference<>();
        volatile String durability="dual";
        volatile String duplicateConfirmationHeader;
        Fixture()throws Exception{
            headers.put("X-Four-Project-MySQL-UUID",UUID);headers.put("X-Four-Project-View-ID","view:1");headers.put("X-Four-Project-Epoch","7");
            headers.put("X-Four-Project-Mode","dual");headers.put("X-Four-Project-Membership","a".repeat(64));headers.put("X-Four-Project-Permit","stable-synthetic-permit");
            headers.put("X-Four-Project-Controller-Boot-ID","22222222-2222-4222-8222-222222222222");confirmationHeaders.putAll(headers);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/authority",e->{
                if(!("Bearer "+TOKEN).equals(e.getRequestHeaders().getFirst("Authorization"))){e.sendResponseHeaders(403,-1);e.close();return;}
                headers.forEach((k,v)->e.getResponseHeaders().set(k,v));e.sendResponseHeaders(204,-1);e.close();
            });
            server.createContext("/confirm",e->{
                if(!e.getRequestMethod().equals("POST")||!("Bearer "+TOKEN).equals(e.getRequestHeaders().getFirst("Authorization"))){e.sendResponseHeaders(403,-1);e.close();return;}
                confirmed.set(new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                confirmationHeaders.forEach((k,v)->e.getResponseHeaders().set(k,v));
                if(duplicateConfirmationHeader!=null)e.getResponseHeaders().add(duplicateConfirmationHeader,confirmationHeaders.get(duplicateConfirmationHeader));
                if(durability!=null)e.getResponseHeaders().set("X-Four-Project-Durability",durability);
                e.sendResponseHeaders(204,-1);e.close();
            });server.start();
        }
        AtomicReplicationPermit client(){Properties p=new Properties();p.setProperty("atomic.authority.url","http://127.0.0.1:"+server.getAddress().getPort()+"/authority");p.setProperty("atomic.authority.token",TOKEN);return new AtomicReplicationPermit(p);}
        public void close(){server.stop(0);}
    }
    @Test public void uuidOnlyLegacyAuthorityCannotGrantAtomicWrites()throws Exception{
        try(Fixture f=new Fixture()){
            f.headers.keySet().removeIf(k->!k.equals("X-Four-Project-MySQL-UUID"));
            try{f.client().capture();fail("Legacy authority was accepted");}catch(SQLException expected){ }
        }
    }
    @Test public void otherwiseCompleteSixFieldAuthorityCannotOmitControllerBootIdentity()throws Exception{
        try(Fixture f=new Fixture()){
            f.headers.remove("X-Four-Project-Controller-Boot-ID");assertThrows(SQLException.class,()->f.client().capture());
        }
    }
    @Test public void epochBootPermitAndModeChangesInvalidateEarlierProof()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicReplicationPermit client=f.client();var proof=client.capture();client.verify(proof);
            for(String field:List.of("X-Four-Project-Epoch","X-Four-Project-Permit","X-Four-Project-Mode","X-Four-Project-View-ID","X-Four-Project-Controller-Boot-ID","X-Four-Project-Membership")){
                String prior=f.headers.get(field);f.headers.put(field,field.endsWith("Mode")?"single":field.endsWith("Epoch")?"8":field.endsWith("Boot-ID")?"33333333-3333-4333-8333-333333333333":field.endsWith("Membership")?"b".repeat(64):prior+"-changed");
                try{client.verify(proof);fail("Changed authority evidence accepted: "+field);}catch(SQLException expected){ }
                f.headers.put(field,prior);
            }
        }
    }
    @Test public void committedGlobalGtidAndOperationAreSentForPrivateConfirmation()throws Exception{
        try(Fixture f=new Fixture();Connection database=mysqlQueries()){
            AtomicReplicationPermit client=f.client();var proof=client.capture();String operation=java.util.UUID.randomUUID().toString();
            assertEquals("dual",client.confirm(database,proof,operation));
            JSONObject sent=f.confirmed.get();assertEquals(operation,sent.getString("operationId"));assertEquals(UUID+":1-5",sent.getString("gtidSet"));assertEquals(proof.permit(),sent.getString("permit"));
        }
    }
    @Test public void missingOrDowngradedDurabilityCannotAcknowledgeDualProof()throws Exception{
        try(Fixture f=new Fixture();Connection database=mysqlQueries()){
            AtomicReplicationPermit client=f.client();var proof=client.capture();
            for(String mode:new String[]{"single",null}){
                f.durability=mode;
                try{client.confirm(database,proof,java.util.UUID.randomUUID().toString());fail("Incomplete confirmation acknowledged");}catch(SQLException expected){ }
            }
        }
    }
    @Test public void confirmationRequiresEveryCapturedFieldAndRejectsMissingWrongOrDuplicateHeaders()throws Exception{
        try(Fixture f=new Fixture();Connection database=mysqlQueries()){
            AtomicReplicationPermit client=f.client();var captured=client.capture();
            for(String field:new ArrayList<>(f.confirmationHeaders.keySet())){
                String original=f.confirmationHeaders.remove(field);
                assertThrows("Missing confirmation field "+field,SQLException.class,()->client.confirm(database,captured,java.util.UUID.randomUUID().toString()));
                f.confirmationHeaders.put(field,changed(field,original));
                assertThrows("Wrong confirmation field "+field,SQLException.class,()->client.confirm(database,captured,java.util.UUID.randomUUID().toString()));
                f.confirmationHeaders.put(field,original);f.duplicateConfirmationHeader=field;
                assertThrows("Duplicate confirmation field "+field,SQLException.class,()->client.confirm(database,captured,java.util.UUID.randomUUID().toString()));
                f.duplicateConfirmationHeader=null;
            }
            assertEquals("dual",client.confirm(database,captured,java.util.UUID.randomUUID().toString()));
        }
    }
    @Test public void swappedSemanticallyValidProofFieldsCannotAcknowledgeTheCommit()throws Exception{
        try(Fixture f=new Fixture();Connection database=mysqlQueries()){
            AtomicReplicationPermit client=f.client();var captured=client.capture();
            String mysql=f.confirmationHeaders.get("X-Four-Project-MySQL-UUID"),boot=f.confirmationHeaders.get("X-Four-Project-Controller-Boot-ID");
            f.confirmationHeaders.put("X-Four-Project-MySQL-UUID",boot);f.confirmationHeaders.put("X-Four-Project-Controller-Boot-ID",mysql);
            assertThrows(SQLException.class,()->client.confirm(database,captured,java.util.UUID.randomUUID().toString()));
            f.confirmationHeaders.put("X-Four-Project-MySQL-UUID",mysql);f.confirmationHeaders.put("X-Four-Project-Controller-Boot-ID",boot);
            String view=f.confirmationHeaders.get("X-Four-Project-View-ID"),permit=f.confirmationHeaders.get("X-Four-Project-Permit");
            f.confirmationHeaders.put("X-Four-Project-View-ID",permit);f.confirmationHeaders.put("X-Four-Project-Permit",view);
            assertThrows(SQLException.class,()->client.confirm(database,captured,java.util.UUID.randomUUID().toString()));
        }
    }
    private static String changed(String field,String value){
        if(field.endsWith("UUID")||field.endsWith("Boot-ID"))return "44444444-4444-4444-8444-444444444444";
        if(field.endsWith("Epoch"))return "8";if(field.endsWith("Mode"))return "single";if(field.endsWith("Membership"))return "b".repeat(64);return value+"-other";
    }
    @Test public void privateCaptureVerifyAndConfirmationNeverConsultDefaultProxySelector()throws Exception{
        ProxySelector previous=ProxySelector.getDefault();AtomicInteger selected=new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector(){
            @Override public List<java.net.Proxy> select(java.net.URI uri){selected.incrementAndGet();throw new AssertionError("Private authority consulted a proxy selector");}
            @Override public void connectFailed(java.net.URI uri,java.net.SocketAddress address,java.io.IOException error){throw new AssertionError("Private authority consulted a proxy");}
        });
        try(Fixture f=new Fixture();Connection database=mysqlQueries()){
            AtomicReplicationPermit client=f.client();var captured=client.capture();client.verify(captured);assertEquals("dual",client.confirm(database,captured,java.util.UUID.randomUUID().toString()));assertEquals(0,selected.get());
        }finally{ProxySelector.setDefault(previous);}
    }
    @Test public void nonUtcSessionsCannotPassDurabilityPrerequisites()throws Exception{
        for(String zone:new String[]{"SYSTEM","+08:00"})try(Connection database=mysqlQueries(zone,28800)){
            assertThrows(SQLException.class,()->AtomicFinanceTransactions.verifyDurability(database));
        }
        try(Connection database=mysqlQueries("+00:00",28800)){assertThrows(SQLException.class,()->AtomicFinanceTransactions.verifyDurability(database));}
    }
    private static Connection mysqlQueries()throws Exception{return mysqlQueries("+00:00",0);}
    private static Connection mysqlQueries(String zone,int offset)throws Exception{
        Connection raw=DriverManager.getConnection("jdbc:h2:mem:permit_"+java.util.UUID.randomUUID());
        return (Connection)Proxy.newProxyInstance(AtomicReplicationPermitTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try{
                Object value=m.invoke(raw,a);
                if(!(value instanceof Statement statement))return value;
                return Proxy.newProxyInstance(AtomicReplicationPermitTest.class.getClassLoader(),new Class[]{Statement.class},(s,q,v)->{
                    try{
                        if(q.getName().equals("executeQuery")){
                            String sql=(String)v[0];
                            if(sql.startsWith("SELECT @@server_uuid"))return statement.executeQuery("SELECT '"+UUID+"',1,'BEFORE_AND_AFTER'");
                            if(sql.startsWith("SELECT @@GLOBAL.innodb_flush_log_at_trx_commit"))return statement.executeQuery("SELECT 1,1,'ON','ON','"+zone+"',"+offset);
                            if(sql.equals("SELECT @@GLOBAL.gtid_executed"))return statement.executeQuery("SELECT '"+UUID+":1-5'");
                            throw new SQLException("Unexpected synthetic query");
                        }
                        return q.invoke(statement,v);
                    }catch(InvocationTargetException error){throw error.getCause();}
                });
            }catch(InvocationTargetException error){throw error.getCause();}
        });
    }
}
