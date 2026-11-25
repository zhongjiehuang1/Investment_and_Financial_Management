package Controller.IncomeAndExpenditureData;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/editIncomeOrExpense")
public class EditIncomeOrExpense extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            return ApiSupport.result(Model.Controller.IncomeAndExpenditureData.EditIncomeOrExpense.editIncomeOrExpense(
                    user.getId(), ApiSupport.positiveInt(input, "id", -1), ApiSupport.text(input, "type"),
                    ApiSupport.text(input, "platform"), ApiSupport.text(input, "name"),
                    ApiSupport.text(input, "dateOfMonth"), ApiSupport.text(input, "monthOfYear"),
                    ApiSupport.positiveInt(input, "year", -1), ApiSupport.text(input, "amount")));
        });
    }
}
