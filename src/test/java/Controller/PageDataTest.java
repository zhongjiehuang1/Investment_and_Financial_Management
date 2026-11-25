package Controller;

import Model.User;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PageDataTest {
    @Test public void overviewUsesTheSameOwnerAndAStableBoundedRecentQuery() throws Exception {
        User user=new User();user.setId(7);List<String> calls=new ArrayList<>();
        JSONObject output=PageData.bundle(user,new JSONObject().put("view","overview"),(kind,owner,input)->{
            assertSame(user,owner);calls.add(kind);
            if(kind.equals("recent")){assertEquals(1,input.getInt("page"));assertEquals(5,input.getInt("pageSize"));}
            return new JSONObject().put("component",kind);
        });
        assertEquals(List.of("report","recent"),calls);assertEquals("overview",output.getString("view"));
        assertTrue(output.has("report")&&output.has("recent"));
    }
    @Test public void assetsAndTransactionFiltersReachOnlyTheirExistingReaders() throws Exception {
        JSONObject assets=new JSONObject().put("kind","nonInvestment").put("page",2).put("q","cash");
        List<String> calls=new ArrayList<>();
        PageData.bundle(new User(),new JSONObject().put("view","assets").put("assets",assets),(kind,user,input)->{
            calls.add(kind);if(kind.equals("assets"))assertSame(assets,input);return new JSONObject();
        });
        assertEquals(List.of("assets","report"),calls);calls.clear();
        JSONObject transactions=new JSONObject().put("page",3),cashflow=new JSONObject().put("section","transfers");
        PageData.bundle(new User(),new JSONObject().put("view","transactions").put("transactions",transactions).put("cashflow",cashflow),(kind,user,input)->{
            calls.add(kind);if(kind.equals("transactions"))assertSame(transactions,input);if(kind.equals("cashflow"))assertSame(cashflow,input);
            if(kind.equals("platforms"))assertEquals(100,input.getInt("pageSize"));return new JSONObject();
        });
        assertEquals(List.of("transactions","cashflow","platforms"),calls);
    }
    @Test public void invalidViewsOrFilterShapesFailBeforeReadingAndAComponentFailureIsNotPartialSuccess() throws Exception {
        PageData.Reader unexpected=(kind,user,input)->{throw new AssertionError("Invalid request reached a reader");};
        assertThrows(IllegalArgumentException.class,()->PageData.bundle(new User(),new JSONObject().put("view","/deleteUser"),unexpected));
        assertThrows(IllegalArgumentException.class,()->PageData.bundle(new User(),new JSONObject().put("view","transactions").put("cashflow","wrong"),unexpected));
        assertThrows(java.sql.SQLException.class,()->PageData.bundle(new User(),new JSONObject().put("view","overview"),(kind,user,input)->{
            if(kind.equals("recent"))throw new java.sql.SQLException("Synthetic read failure");return new JSONObject();
        }));
    }
}
