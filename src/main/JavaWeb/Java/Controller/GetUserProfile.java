package Controller;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet({"/getUserProfile", "/api/v1/profile"})
public class GetUserProfile extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, false, (user, input) -> Model.Controller.ReadData.profile(user, input)
                .put("isAdmin", ApiSupport.isAdmin(user))
                .put("currencies", Model.FixedTypes.Currencies.supported()));
    }
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        doGet(request, response);
    }
}
