package Controller.AssetsData.NonInvestmentAsset;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/deleteNonInvestmentAsset")
public class DeleteAsset extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            return ApiSupport.result(Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(
                    user.getId(), ApiSupport.positiveInt(input, "id", -1)));
        });
    }
}
