package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Deletes only the currently authenticated account and its owned financial records. */
@WebServlet("/deleteUser")
public class DeleteUser extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            Controller.DeletionResults.prepare(request,user);
            return ApiSupport.result(Model.Controller.RegisterAndLogin.DeleteUser.deleteUser(user));
        },(user,result)->Controller.DeletionResults.finish(request,user,result));
    }
}
