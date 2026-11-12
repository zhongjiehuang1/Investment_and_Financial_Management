package Controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;
import org.junit.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ServerStatusTest {
    @Test public void missingAtomicEnvironmentLeavesStaticIdentityAvailableButCannotAdvertiseReady(){
        String prior=System.getProperty("iafm.cluster.enabled"),priorId=System.getProperty("iafm.server.id");
        try{
            System.setProperty("iafm.cluster.enabled","false");System.setProperty("iafm.server.id","1");
            for(String path:new String[]{"/api/server","/api/cluster-health"}){
                AtomicInteger code=new AtomicInteger(200);StringWriter output=new StringWriter();
                HttpServletRequest req=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->m.getName().equals("getServletPath")?path:null);
                HttpServletResponse res=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,a)->switch(m.getName()){
                    case "setStatus"->{code.set((int)a[0]);yield null;}case "getWriter"->new PrintWriter(output);default->null;});
                new ServerStatus().doGet(req,res);JSONObject result=new JSONObject(output.toString());
                if(path.equals("/api/server")){assertEquals(200,code.get());assertEquals(1,result.getInt("serverId"));}
                else{assertEquals(503,code.get());assertEquals("unavailable",result.getString("status"));assertFalse(result.has("replicationMode"));}
            }
        }finally{if(prior==null)System.clearProperty("iafm.cluster.enabled");else System.setProperty("iafm.cluster.enabled",prior);if(priorId==null)System.clearProperty("iafm.server.id");else System.setProperty("iafm.server.id",priorId);}
    }
}
