package Controller;

import Controller.ApiSupport;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet({"/getUserProfile", "/api/v1/profile"})
public class GetUserProfile extends HttpServlet {
    private static JSONObject localizedName(JSONObject profile) {
        String first = profile.optString("forename", "").trim();
        String last = profile.optString("surname", "").trim();
        String name = "";
        if (last.equalsIgnoreCase("Huang")) {
            if (first.equalsIgnoreCase("Zhongjie")) name = "黄中杰";
            else if (first.equalsIgnoreCase("Rong")) name = "黄蓉";
        }
        return profile.put("displayNameZh", name);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, false, (user, input) -> localizedName(Model.Controller.ReadData.profile(user, input))
                .put("isAdmin", ApiSupport.isAdmin(user))
                .put("currencies", Model.FixedTypes.Currencies.supported()));
    }
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        doGet(request, response);
    }
}
