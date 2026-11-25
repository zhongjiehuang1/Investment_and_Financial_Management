package Controller.InflationRatesData;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/deleteInflationRate")
public class DeleteInflationRate extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            ApiSupport.requireAdmin(user);
            return ApiSupport.result(Model.Controller.InflationRatesData.DeleteInflationRate.deleteInflationRate(ApiSupport.positiveInt(input, "id", -1)));
        });
    }
}
