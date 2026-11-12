package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/register")
public class Register extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.prepare(response);
        try {
            request.setCharacterEncoding("UTF-8");
            ApiSupport.checkOrigin(request);
            JSONObject input = ApiSupport.input(request);
            String email = ApiSupport.text(input, "regEmailAddress");
            String password = input.getString("regPassword");
            String surname = ApiSupport.text(input, "lastName"), forename = ApiSupport.text(input, "firstName");
            if (password.isEmpty() || password.length() > 1024 || surname.length() > 50 || forename.length() > 50
                    || !Model.Controller.RegisterAndLogin.ConditionalityCheck.isEmailAddressValid(email))
                throw new IllegalArgumentException("Invalid registration details.");
            if (!LoginThrottle.allow(LoginThrottle.client(request), System.currentTimeMillis()))
                throw new ApiSupport.Failure(429, "Too many requests. Try again in five minutes.");
            DatabaseService.FinanceCluster.Work registration = () -> {
                boolean success = Model.Controller.RegisterAndLogin.Register.userRegister(email, password, surname, forename);
                if(!success)throw new ApiSupport.Failure(409,"Registration could not be completed. Check your details or use another email.");
                return new JSONObject().put("status","success").put("message","Account created.");
            };
            JSONObject result=DatabaseService.FinanceCluster.enabled()
                    ? DatabaseService.FinanceCluster.get().run(request,null,input,true,registration) : registration.run();
            ApiSupport.write(response,result);
        } catch (Controller.ClusterAvailability.Unavailable failure) {
            response.setStatus(503);
            ApiSupport.write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",Controller.ClusterAvailability.MESSAGE));
        } catch (ApiSupport.Failure failure) {
            response.setStatus(failure.httpStatus());
            ApiSupport.write(response, new JSONObject().put("status", "failure").put("message", failure.getMessage()));
        } catch (IllegalArgumentException | org.json.JSONException invalid) {
            response.setStatus(400);
            ApiSupport.write(response, new JSONObject().put("status", "failure").put("message", "Enter valid registration details."));
        } catch (Exception failure) {
            response.setStatus(503);
            ApiSupport.write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",Controller.ClusterAvailability.MESSAGE));
        }
    }
}
