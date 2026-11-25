package Controller.AssetsData.InvestmentAsset;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/deleteInvestmentAsset")
public class DeleteAsset extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            return ApiSupport.result(Model.Controller.AssetsData.InvestmentAsset.DeleteAsset.deleteInvestmentAsset(
                    user.getId(), ApiSupport.positiveInt(input, "id", -1)));
        });
    }
}
