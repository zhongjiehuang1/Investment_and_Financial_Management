package Model.Controller.RegisterAndLogin;

public final class DeleteUser {
    private DeleteUser() { }
    public static boolean deleteUser(Model.User user) {
        if (user == null || user.getId() <= 0 || !DatabaseService.RequestIdentity.validGeneration(user.getGeneration()))
            throw new IllegalArgumentException("A current authenticated user is required.");
        return DatabaseService.Model.User.deleteUser(user.getId(), user.getGeneration());
    }
}
