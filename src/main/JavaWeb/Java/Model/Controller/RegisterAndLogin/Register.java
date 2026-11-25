package Model.Controller.RegisterAndLogin;

public class Register {

    public static boolean userRegister(
            java.lang.String emailAddress,
            java.lang.String password, java.lang.String surname, java.lang.String forename) {
        if (!Model.Controller.RegisterAndLogin.ConditionalityCheck.accountAlreadyExist(emailAddress)) {
            return DatabaseService.Model.Controller.RegisterAndLogin.Register.userRegister(
                    emailAddress, password, surname, forename);
        } else {
            return false;
        }
    }

}
