package Controller.IncomeAndExpenditureData;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/transferBetweenAccounts")
public class TransferBetweenAccounts extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            Object received = input.opt("receivedAmount");
            if (received != null && !(received instanceof String))
                throw new IllegalArgumentException("receivedAmount must be a decimal String");
            return ApiSupport.result(Model.Controller.IncomeAndExpenditureData.TransferBetweenAccounts.transferBetweenAccounts(
                    user.getId(), ApiSupport.text(input, "fromPlatform"), ApiSupport.text(input, "toPlatform"),
                    ApiSupport.text(input, "amount"), input.optString("receivedAmount", ""),
                    ApiSupport.text(input, "date"), input.optString("name", "Internal transfer")));
        });
    }
}
