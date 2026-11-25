package Controller;
import org.junit.Test;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class BusyResponseTest {
 @Test public void overloadIsNotUnavailableOrACommittedOperation(){Map<String,String> headers=new HashMap<>();int[] status={0};StringWriter body=new StringWriter();HttpServletResponse response=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,args)->{if(m.getName().equals("setStatus"))status[0]=(int)args[0];if(m.getName().equals("setHeader"))headers.put((String)args[0],(String)args[1]);if(m.getName().equals("getWriter"))return new PrintWriter(body);return null;});ApiSupport.busy(response);JSONObject result=new JSONObject(body.toString());assertEquals(429,status[0]);assertEquals("1",headers.get("Retry-After"));assertEquals("NOT_STARTED",result.getString("dispatchState"));assertEquals("SERVER_BUSY",result.getString("code"));assertFalse(result.has("durability"));assertFalse(result.has("operationStatus"));}
}
