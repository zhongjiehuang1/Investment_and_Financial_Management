package Controller.AssetsData.NonInvestmentAsset.AddAsset;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/addNonInvestmentAsset")
public class Servlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            Command command = new Command();
            command.setAssetName(ApiSupport.text(input, "assetName"));
            command.setAssetType(ApiSupport.text(input, "assetType"));
            command.setRiskType(ApiSupport.text(input, "riskType"));
            command.setAmount(ApiSupport.text(input, "amount"));
            command.setCurrency(ApiSupport.text(input, "currency"));
            return ApiSupport.result(command.runCommand(user.getId(), command));
        });
    }
}
