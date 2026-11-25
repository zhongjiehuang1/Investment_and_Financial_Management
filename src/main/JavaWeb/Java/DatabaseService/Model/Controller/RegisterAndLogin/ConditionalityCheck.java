package DatabaseService.Model.Controller.RegisterAndLogin;

import DatabaseService.DatabaseInit;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ConditionalityCheck extends DatabaseInit {

    private static final java.lang.String checkUserDetails = "SELECT EXISTS(SELECT 1 FROM USERS WHERE EMAIL_ADDRESS " +
            "= ?) AS VALUE_EXISTS";

    public static boolean accountAlreadyExist(java.lang.String emailAddress) {
        java.sql.Connection connection = openPrimaryConnection();
        PreparedStatement[] preparedStatements = getPreparedStatements(1);
        java.sql.ResultSet[] resultSets = getResultSets(1);
        try {
            preparedStatements[0] = connection.prepareStatement(checkUserDetails);

            preparedStatements[0].setString(1, emailAddress);

            resultSets[0] = preparedStatements[0].executeQuery();

            if (resultSets[0].next()) {
                return resultSets[0].getInt("VALUE_EXISTS") == 1;
            } else {
                System.out.println("执行ResultSet操作时发生错误), (DatabaseService/Model/Controller/RegisterAndLogin/Conditio" +
                        "nalityCheck.java)");
                return true;
            }
        } catch (SQLException sqlException) {
            System.out.println("执行SQL操作时发生错误: " + sqlException.getMessage() + " (DatabaseService/Model/Controller/Re" +
                    "gisterAndLogin/ConditionalityCheck.java)");
            return true;
        } finally {
            releaseResources(connection, preparedStatements, resultSets);
        }
    }

}
