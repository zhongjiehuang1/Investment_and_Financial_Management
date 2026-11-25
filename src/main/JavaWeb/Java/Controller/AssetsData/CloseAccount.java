package Controller.AssetsData;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@WebServlet({"/closeInvestmentAsset", "/closeNonInvestmentAsset"})
public final class CloseAccount extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            LocalDate day;
            try {
                day = LocalDate.parse(ApiSupport.text(input, "closedOn"));
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Invalid closing date.");
            }
            return ApiSupport.result(DatabaseService.Model.Controller.AssetsData.CloseAccount.close(user.getId(),
                    ApiSupport.positiveInt(input, "id", -1), "/closeInvestmentAsset".equals(request.getServletPath()), day));
        });
    }
}
