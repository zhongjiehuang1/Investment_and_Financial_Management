package Model.Controller.RegisterAndLogin;

public class Login {

    public static boolean userLogin(Model.User user, java.lang.String username, java.lang.String password) {
        return DatabaseService.Model.Controller.RegisterAndLogin.Login.userLogin(user, username, password);
    }

}
