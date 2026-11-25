package Controller;

import DatabaseService.Model.Controller.SavedPeriodReports;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;

@WebServlet({"/getPeriodReportInput", "/deletePeriodReport"})
public final class PeriodReportActions extends HttpServlet {
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        boolean delete = request.getServletPath().equals("/deletePeriodReport");
        ApiSupport.serve(request, response, delete,
                (user, input) -> {
                    String id = input.optString("reportId", "");
                    if (!delete) return new JSONObject().put("input", SavedPeriodReports.input(user, id));
                    SavedPeriodReports.delete(user, id);
                    return new JSONObject();
                });
    }
}
