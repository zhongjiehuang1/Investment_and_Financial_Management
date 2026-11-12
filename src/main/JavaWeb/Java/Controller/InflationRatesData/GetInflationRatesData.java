package Controller.InflationRatesData;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet({"/getInflationRatesData", "/api/v1/inflation-rates"})
public class GetInflationRatesData extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, false, (user, input) -> Model.Controller.ReadData.inflationPage(user, input).put("canEdit", ApiSupport.isAdmin(user)));
    }
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        doGet(request, response);
    }
}
