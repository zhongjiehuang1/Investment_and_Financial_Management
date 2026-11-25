package Controller;

import Model.User;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;
import java.util.List;

/** One authenticated, revision-bound transaction supplies one complete page. */
@WebServlet("/pageData")
public final class PageData extends HttpServlet {
    @FunctionalInterface interface Reader { JSONObject read(String component, User user, JSONObject input) throws Exception; }
    private record Part(String name, JSONObject input) { }
    private static JSONObject input(JSONObject request,String key) {
        if(!request.has(key))return new JSONObject();
        if(!(request.get(key) instanceof JSONObject value))throw new IllegalArgumentException("Invalid page filters");
        return value;
    }
    static JSONObject bundle(User user,JSONObject request,Reader reader)throws Exception {
        List<Part> parts=switch(request.optString("view","")) {
            case "overview" -> List.of(new Part("report",new JSONObject()),new Part("recent",new JSONObject().put("page",1).put("pageSize",5)));
            case "assets" -> List.of(new Part("assets",input(request,"assets")),new Part("report",new JSONObject()));
            case "transactions" -> List.of(new Part("transactions",input(request,"transactions")),new Part("cashflow",input(request,"cashflow")),new Part("platforms",new JSONObject().put("page",1).put("pageSize",100).put("includeClosed",true)));
            default -> throw new IllegalArgumentException("Invalid page view");
        };
        JSONObject result=new JSONObject().put("view",request.getString("view"));
        for(Part part:parts)result.put(part.name,reader.read(part.name,user,part.input));
        return result;
    }
    public static JSONObject read(User user,JSONObject request)throws Exception {
        return bundle(user,request,(component,owner,input)->switch(component) {
            case "report" -> Model.Controller.ReadData.financialReport(owner,input);
            case "recent","transactions" -> Model.Controller.ReadData.transactionsPage(owner,input);
            case "assets" -> Model.Controller.ReadData.assetsPage(owner,input);
            case "cashflow" -> Model.Controller.ReadData.cashflowSummary(owner,input);
            case "platforms" -> Model.Controller.ReadData.platformsPage(owner,input);
            default -> throw new IllegalArgumentException("Invalid page component");
        });
    }
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) {
        ApiSupport.serve(request,response,false,PageData::read);
    }
}
