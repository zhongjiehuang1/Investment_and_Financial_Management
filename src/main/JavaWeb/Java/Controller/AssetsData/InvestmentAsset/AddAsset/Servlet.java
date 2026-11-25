package Controller.AssetsData.InvestmentAsset.AddAsset;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/addInvestmentAsset")
public class Servlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            Command command = new Command();
            command.setInvestmentPlatform(ApiSupport.text(input, "investmentPlatform"));
            command.setInvestmentType(ApiSupport.text(input, "investmentType"));
            command.setRiskType(ApiSupport.text(input, "riskType"));
            command.setInvestmentAmount(ApiSupport.text(input, "investmentAmount"));
            command.setCashWithdrawal(ApiSupport.text(input, "cashWithdrawal"));
            command.setBuyInAmount(ApiSupport.text(input, "buyInAmount"));
            command.setAmountSold(ApiSupport.text(input, "amountSold"));
            command.setManagementFee(ApiSupport.text(input, "managementFee"));
            command.setHandlingFee(ApiSupport.text(input, "handlingFee"));
            command.setRealTimeAmount(ApiSupport.text(input, "realTimeAmount"));
            command.setCurrency(ApiSupport.text(input, "currency"));
            command.setInitialEarnings(ApiSupport.text(input, "initialEarnings"));
            return ApiSupport.result(command.runCommand(user.getId(), command));
        });
    }
}
